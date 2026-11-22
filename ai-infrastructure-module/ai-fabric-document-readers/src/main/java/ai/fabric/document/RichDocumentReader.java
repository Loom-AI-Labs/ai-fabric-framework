package ai.fabric.document;

import ai.fabric.indexing.document.springai.SpringAiTrustedResourcePolicy;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipFile;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.ai.document.Document;
import org.springframework.ai.document.DocumentReader;
import org.springframework.core.io.Resource;

/** Local, bounded extraction. Format identifiers and resource trust are server-owned. */
public final class RichDocumentReader implements DocumentReader {
    public static final Set<String> FORMATS = Set.of("pdf", "docx", "doc", "png", "jpg", "jpeg", "tif", "tiff");
    private final Resource resource;
    private final String format;
    private final DocumentReadOptions options;
    private final OcrEngine ocr;
    private Report report;

    public RichDocumentReader(Resource resource, SpringAiTrustedResourcePolicy trust, String format,
        DocumentReadOptions options, OcrEngine ocr) {
        Objects.requireNonNull(trust, "Resource trust policy is required").validate(resource);
        this.resource = Objects.requireNonNull(resource);
        this.format = Objects.requireNonNull(format).replaceFirst("^\\.", "").toLowerCase(Locale.ROOT);
        if (!FORMATS.contains(this.format)) throw new DocumentReadException("DOCUMENT_FORMAT_UNSUPPORTED", "This document format is not supported.");
        this.options = Objects.requireNonNull(options);
        this.ocr = ocr;
    }

    @Override public List<Document> get() { return read(); }

    @Override public List<Document> read() {
        long deadline = System.nanoTime() + options.timeout().toNanos();
        List<Document> documents = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        try {
            if (resource.contentLength() > options.maxBytes()) throw limit("Document exceeds its byte allowance.");
            byte[] bytes;
            try (var input = resource.getInputStream()) {
                bytes = input.readNBytes((int) options.maxBytes() + 1);
            }
            if (bytes.length > options.maxBytes()) throw limit("Document exceeds its byte allowance.");
            if (bytes.length == 0) throw new DocumentReadException("DOCUMENT_EMPTY", "The document is empty.");
            switch (format) {
                case "pdf" -> readPdf(bytes, documents, warnings, deadline);
                case "docx" -> readDocx(bytes, documents, deadline);
                case "doc" -> readDoc(bytes, documents, deadline);
                default -> readImage(bytes, documents, warnings, deadline);
            }
            int count = documents.stream().mapToInt(d -> d.getText().length()).sum();
            if (count == 0) throw new DocumentReadException("DOCUMENT_NO_TEXT", "No readable text was extracted. Review the file and OCR settings.");
            checkTime(deadline);
            report = new Report(format, documents.size(), count,
                (int) documents.stream().filter(d -> "OCR".equals(d.getMetadata().get("extractionMethod"))).count(), List.copyOf(warnings));
            return List.copyOf(documents);
        } catch (DocumentReadException exception) {
            throw exception;
        } catch (InvalidPasswordException exception) {
            throw new DocumentReadException("DOCUMENT_ENCRYPTED", "Password-protected documents are not supported.", exception);
        } catch (Exception exception) {
            throw new DocumentReadException("DOCUMENT_PARSE_FAILED", "The document could not be parsed. Check the format or remove encryption.", exception);
        }
    }

    public Report report() {
        if (report == null) throw new IllegalStateException("Read the document before requesting extraction diagnostics");
        return report;
    }

    private void readPdf(byte[] bytes, List<Document> result, List<String> warnings, long deadline) throws IOException {
        requireMagic(bytes, new byte[]{'%', 'P', 'D', 'F'}, "PDF");
        try (var pdf = Loader.loadPDF(bytes)) {
            if (pdf.isEncrypted()) throw new DocumentReadException("DOCUMENT_ENCRYPTED", "Encrypted documents are not supported.");
            if (pdf.getNumberOfPages() > options.maxPages()) throw limit("PDF exceeds its page allowance.");
            var renderer = new PDFRenderer(pdf);
            for (int index = 0; index < pdf.getNumberOfPages(); index++) {
                checkTime(deadline);
                var stripper = new PDFTextStripper();
                stripper.setSortByPosition(true); stripper.setStartPage(index + 1); stripper.setEndPage(index + 1);
                String text = stripper.getText(pdf);
                boolean useOcr = options.ocrMode() == DocumentReadOptions.OcrMode.ALWAYS
                    || options.ocrMode() == DocumentReadOptions.OcrMode.AUTO && text.strip().length() < options.minimumPdfTextCharacters();
                if (useOcr) {
                    var rectangle = pdf.getPage(index).getCropBox();
                    checkPixels(Math.ceil(rectangle.getWidth() * options.dpi() / 72d), Math.ceil(rectangle.getHeight() * options.dpi() / 72d));
                    BufferedImage image = renderer.renderImageWithDPI(index, options.dpi());
                    try { text = recognize(image, deadline, remainingCharacters(result)); } finally { image.flush(); }
                }
                if (text.isBlank()) { warnings.add("PAGE_" + (index + 1) + "_NO_TEXT"); continue; }
                add(result, text, index + 1, useOcr ? "OCR" : "TEXT");
            }
        }
    }

    private void readDocx(byte[] bytes, List<Document> result, long deadline) throws IOException {
        requireMagic(bytes, new byte[]{'P', 'K'}, "DOCX");
        // Inspect archive sizes before XML parsing; POI retains its own zip-bomb protection too.
        if (!resource.isFile()) throw new DocumentReadException("DOCUMENT_RESOURCE_UNTRUSTED", "Rich Word parsing requires a trusted materialized file.");
        try (var zip = new ZipFile(resource.getFile())) {
            long total = 0; int count = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                checkTime(deadline);
                var entry = entries.nextElement();
                if (++count > 2000 || entry.getSize() < 0 || entry.getSize() > 20L * 1024 * 1024)
                    throw limit("Word archive exceeds its expanded-entry allowance.");
                total += entry.getSize();
                if (total > 40L * 1024 * 1024 || entry.getSize() > 1_000_000
                    && entry.getSize() / (double) Math.max(1, entry.getCompressedSize()) > 100)
                    throw limit("Word archive exceeds its expansion allowance.");
            }
        }
        try (var word = new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new XWPFWordExtractor(word)) {
            add(result, extractor.getText(), 1, "WORD_TEXT");
        }
    }

    private void readDoc(byte[] bytes, List<Document> result, long deadline) throws IOException {
        requireMagic(bytes, new byte[]{(byte)0xD0,(byte)0xCF,0x11,(byte)0xE0}, "DOC");
        checkTime(deadline);
        try (var extractor = new WordExtractor(new ByteArrayInputStream(bytes))) { add(result, extractor.getText(), 1, "WORD_TEXT"); }
    }

    private void readImage(byte[] bytes, List<Document> result, List<String> warnings, long deadline) throws IOException {
        if (options.ocrMode() == DocumentReadOptions.OcrMode.OFF)
            throw new DocumentReadException("OCR_REQUIRED", "Image documents require OCR to extract text.");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new DocumentReadException("DOCUMENT_FORMAT_MISMATCH", "The image content does not match a supported format.");
            var reader = readers.next();
            try {
                String actual = reader.getFormatName().toLowerCase(Locale.ROOT);
                boolean matches = actual.equals(format) || Set.of("jpg","jpeg").contains(actual) && Set.of("jpg","jpeg").contains(format)
                    || Set.of("tif","tiff").contains(actual) && Set.of("tif","tiff").contains(format);
                if (!matches) throw new DocumentReadException("DOCUMENT_FORMAT_MISMATCH", "The image format does not match its registered type.");
                reader.setInput(input);
                int pages = reader.getNumImages(true);
                if (pages < 1 || pages > options.maxPages()) throw limit("Image document exceeds its page allowance.");
                for (int index = 0; index < pages; index++) {
                    checkTime(deadline); checkPixels(reader.getWidth(index), reader.getHeight(index));
                    BufferedImage image = reader.read(index);
                    try {
                        String text = recognize(image, deadline, remainingCharacters(result));
                        if (text.isBlank()) warnings.add("PAGE_" + (index + 1) + "_NO_TEXT");
                        else add(result, text, index + 1, "OCR");
                    } finally { image.flush(); }
                }
            } finally { reader.dispose(); }
        }
    }

    private String recognize(BufferedImage image, long deadline, int remainingCharacters) {
        if (ocr == null) throw new DocumentReadException("OCR_UNAVAILABLE", "This document needs OCR, but no OCR engine is configured.");
        return ocr.recognize(image, options.language(), Duration.ofNanos(Math.max(1, deadline - System.nanoTime())), remainingCharacters);
    }

    private void add(List<Document> result, String value, int page, String method) {
        String text = value == null ? "" : value.strip();
        if (text.length() > remainingCharacters(result)) throw limit("Extracted text exceeds its character allowance.");
        if (text.isBlank()) return;
        Map<String,Object> metadata = new LinkedHashMap<>();
        if (!Set.of("doc","docx").contains(format)) metadata.put("pageNumber", page);
        metadata.put("extractionMethod", method); metadata.put("documentFormat", format);
        result.add(Document.builder().id("section-" + page).text(text).metadata(metadata).build());
    }

    private int remainingCharacters(List<Document> documents) { return options.maxCharacters() - documents.stream().mapToInt(d -> d.getText().length()).sum(); }
    private void checkPixels(double width, double height) {
        if (!Double.isFinite(width) || !Double.isFinite(height) || width <= 0 || height <= 0 || width * height > options.maxImagePixels())
            throw limit("Rendered image exceeds its pixel allowance.");
    }
    private static void checkTime(long deadline) {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new DocumentReadException("DOCUMENT_PARSE_TIMEOUT", "Document extraction exceeded its time allowance.");
    }
    private static void requireMagic(byte[] bytes, byte[] magic, String format) {
        if (bytes.length < magic.length) throw new DocumentReadException("DOCUMENT_FORMAT_MISMATCH", format + " content is invalid.");
        for (int i = 0; i < magic.length; i++) if (bytes[i] != magic[i])
            throw new DocumentReadException("DOCUMENT_FORMAT_MISMATCH", "Document content does not match its registered format.");
    }
    private static DocumentReadException limit(String message) { return new DocumentReadException("DOCUMENT_LIMIT_EXCEEDED", message); }
    public record Report(String format, int textSections, int characters, int ocrSections, List<String> warnings) { }
}
