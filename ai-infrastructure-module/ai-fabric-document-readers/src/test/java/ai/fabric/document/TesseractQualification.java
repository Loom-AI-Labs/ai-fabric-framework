package ai.fabric.document;

import ai.fabric.indexing.document.springai.SpringAiTrustedResourcePolicy;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.core.io.FileSystemResource;

/** Explicit real-engine qualification, separate from fixture-based unit tests. */
public final class TesseractQualification {
    public static void main(String[] args) throws Exception {
        var engine=new TesseractOcrEngine(Path.of(args.length==0?"/usr/bin/tesseract":args[0]),Set.of("eng"));
        var image=new BufferedImage(1400,300,BufferedImage.TYPE_INT_RGB);
        var graphics=image.createGraphics();graphics.setColor(Color.WHITE);graphics.fillRect(0,0,1400,300);
        graphics.setColor(Color.BLACK);graphics.setFont(new Font("DejaVu Sans",Font.PLAIN,42));
        graphics.drawString("Return unused items within 30 days.",50,100);graphics.drawString("Contact the support team before return.",50,180);graphics.dispose();
        Path root=Files.createTempDirectory("rich-document-qualification-");
        try {
            Path scan=root.resolve("scan.png");javax.imageio.ImageIO.write(image,"png",scan.toFile());
            Path pdfFile=root.resolve("scanned.pdf");
            try(var pdf=new PDDocument()) {
                var page=new PDPage();pdf.addPage(page);
                try(var stream=new PDPageContentStream(pdf,page)) {stream.drawImage(LosslessFactory.createFromImage(pdf,image),20,400,560,120);}
                pdf.save(pdfFile.toFile());
            }
            for(Path file:java.util.List.of(scan,pdfFile)) {
                String format=file.getFileName().toString().endsWith("pdf")?"pdf":"png";
                var reader=new RichDocumentReader(new FileSystemResource(file),SpringAiTrustedResourcePolicy.trustedRoot(root),format,DocumentReadOptions.defaults(),engine);
                String text=reader.read().getFirst().getText();
                if(!text.contains("30 days") || reader.report().ocrSections()!=1) throw new AssertionError("Real OCR missed the fixture text: "+text);
                System.out.println("PASS real Tesseract "+format+": "+text.replace('\n',' '));
            }
            if(args.length>1) {
                Path file=Path.of(args[1]).toAbsolutePath();
                var reader=new RichDocumentReader(new FileSystemResource(file),SpringAiTrustedResourcePolicy.trustedRoot(file.getParent()),"doc",DocumentReadOptions.defaults(),engine);
                String text=reader.read().getFirst().getText();
                if(text.isBlank() || reader.report().ocrSections()!=0)throw new AssertionError("Legacy Word text extraction failed.");
                System.out.println("PASS legacy DOC: "+text.substring(0,Math.min(140,text.length())).replace('\n',' '));
            }
        } finally {
            image.flush();try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
        }
    }
}
