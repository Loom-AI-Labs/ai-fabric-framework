package ai.fabric.document;

import ai.fabric.indexing.document.springai.SpringAiTrustedResourcePolicy;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.*;

class RichDocumentReaderTest {
    @TempDir Path root;
    @Test void readsTextPdfWithPageAttribution() throws Exception {
        Path file = root.resolve("guide.pdf");
        try (var pdf = new PDDocument()) {
            for (String text : new String[]{"Returns are accepted within thirty days.", "Contact support before returning a product."}) {
                var page = new PDPage(); pdf.addPage(page);
                try (var stream = new PDPageContentStream(pdf, page)) {
                    stream.beginText(); stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(40, 700); stream.showText(text); stream.endText();
                }
            }
            pdf.save(file.toFile());
        }
        var reader = reader(file, "pdf", null);
        var documents = reader.read();
        assertThat(documents).hasSize(2);
        assertThat(documents.getFirst().getText()).contains("thirty days");
        assertThat(documents.get(1).getMetadata()).containsEntry("pageNumber", 2).containsEntry("extractionMethod", "TEXT");
        assertThat(reader.report().ocrSections()).isZero();
    }
    @Test void readsWordIncludingTableWithoutInventingPageNumbers() throws Exception {
        Path file = root.resolve("guide.docx");
        try (var word = new XWPFDocument(); var output = Files.newOutputStream(file)) {
            word.createParagraph().createRun().setText("Approved support guide");
            word.createTable(1, 1).getRow(0).getCell(0).setText("Refund window: 30 days"); word.write(output);
        }
        var document = reader(file, "docx", null).read().getFirst();
        assertThat(document.getText()).contains("Approved support guide", "30 days");
        assertThat(document.getMetadata()).containsEntry("extractionMethod", "WORD_TEXT").doesNotContainKey("pageNumber");
    }
    @Test void imageOcrIsAnExplicitReplaceableEngine() throws Exception {
        Path file = root.resolve("scan.png");
        ImageIO.write(new BufferedImage(100, 80, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        OcrEngine engine = new OcrEngine() {
            public String id() { return "fixture"; }
            public Set<String> languages() { return Set.of("eng"); }
            public String recognize(BufferedImage image, String language, Duration remaining, int allowance) {
                assertThat(language).isEqualTo("eng"); assertThat(remaining).isPositive();
                return "Scanned approved policy";
            }
        };
        var reader = reader(file, "png", engine);
        assertThat(reader.read().getFirst().getText()).isEqualTo("Scanned approved policy");
        assertThat(reader.report().ocrSections()).isEqualTo(1);
        assertThatThrownBy(() -> reader(file, "png", null).read()).isInstanceOf(DocumentReadException.class).hasMessageContaining("no OCR engine");
    }
    @Test void rejectsMislabeledAndUntrustedFiles() throws Exception {
        Path file = root.resolve("fake.pdf"); Files.writeString(file, "not a pdf");
        assertThatThrownBy(() -> reader(file, "pdf", null).read()).isInstanceOf(DocumentReadException.class).hasMessageContaining("does not match");
        assertThatThrownBy(() -> new RichDocumentReader(new FileSystemResource(file), SpringAiTrustedResourcePolicy.trustedRoot(root.resolve("other")),
            "pdf", DocumentReadOptions.defaults(), null)).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsOversizedImagesBeforeDecodingOcr() throws Exception {
        Path file = root.resolve("scan.png"); ImageIO.write(new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB), "png", file.toFile());
        var defaults = DocumentReadOptions.defaults();
        var options = new DocumentReadOptions(defaults.ocrMode(), "eng", 150, 50, 250000, defaults.maxBytes(), 10000, Duration.ofSeconds(5), 20);
        assertThatThrownBy(() -> new RichDocumentReader(new FileSystemResource(file), SpringAiTrustedResourcePolicy.trustedRoot(root), "png", options, null).read())
            .isInstanceOf(DocumentReadException.class).hasMessageContaining("pixel allowance");
    }
    @Test void encryptedPdfAndZipExpansionAreVisibleFailures() throws Exception {
        Path encrypted=root.resolve("protected.pdf");
        try(var pdf=new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy("owner","password",new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            pdf.save(encrypted.toFile());
        }
        assertThatThrownBy(() -> reader(encrypted,"pdf",null).read()).isInstanceOf(DocumentReadException.class).hasMessageContaining("protected");
        Path expanded=root.resolve("expanded.docx");
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(expanded))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));zip.write(new byte[2_000_000]);zip.closeEntry();
        }
        assertThatThrownBy(() -> reader(expanded,"docx",null).read()).isInstanceOf(DocumentReadException.class).hasMessageContaining("expansion allowance");
    }
    @Test void byteAndPageAllowancesAreCheckedBeforeExtraction() throws Exception {
        Path file=root.resolve("large.pdf");
        try(var pdf=new PDDocument()){pdf.addPage(new PDPage());pdf.addPage(new PDPage());pdf.save(file.toFile());}
        var defaults=DocumentReadOptions.defaults();
        var options=new DocumentReadOptions(defaults.ocrMode(),"eng",150,1,250000,defaults.maxBytes(),defaults.maxImagePixels(),Duration.ofSeconds(5),20);
        assertThatThrownBy(() -> new RichDocumentReader(new FileSystemResource(file),SpringAiTrustedResourcePolicy.trustedRoot(root),"pdf",options,null).read())
            .isInstanceOf(DocumentReadException.class).hasMessageContaining("page allowance");
    }
    @Test void ocrProcessHasABoundedDeadlineAndServerOwnedLanguageAllowlist() throws Exception {
        Path executable=root.resolve("ocr-fixture");
        Files.writeString(executable,"#!/bin/sh\nsleep 10\n");executable.toFile().setExecutable(true);
        var engine=new TesseractOcrEngine(executable,Set.of("eng"));
        var image=new BufferedImage(10,10,BufferedImage.TYPE_INT_RGB);
        assertThatThrownBy(() -> engine.recognize(image,"fra",Duration.ofMillis(100),1000)).isInstanceOf(DocumentReadException.class).hasMessageContaining("not installed");
        assertThatThrownBy(() -> engine.recognize(image,"eng",Duration.ofMillis(100),1000)).isInstanceOf(DocumentReadException.class).hasMessageContaining("time allowance");
    }
    private RichDocumentReader reader(Path file, String format, OcrEngine ocr) {
        return new RichDocumentReader(new FileSystemResource(file), SpringAiTrustedResourcePolicy.trustedRoot(root), format, DocumentReadOptions.defaults(), ocr);
    }
}
