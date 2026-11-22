package ai.fabric.document;

import java.time.Duration;

/** Server-authorized parsing and OCR limits; never derived from document content. */
public record DocumentReadOptions(OcrMode ocrMode, String language, int dpi, int maxPages,
    int maxCharacters, long maxBytes, long maxImagePixels, Duration timeout, int minimumPdfTextCharacters) {
    public enum OcrMode { AUTO, ALWAYS, OFF }
    public DocumentReadOptions {
        if (ocrMode == null || language == null || !language.matches("[a-z]{3}(\\+[a-z]{3}){0,3}"))
            throw new IllegalArgumentException("An explicit OCR mode and bounded language identifier are required");
        if (dpi < 72 || dpi > 300 || maxPages < 1 || maxPages > 500 || maxCharacters < 1
            || maxCharacters > 5_000_000 || maxBytes < 1 || maxBytes > 50L * 1024 * 1024
            || maxImagePixels < 1 || maxImagePixels > 40_000_000 || timeout == null
            || timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(2)) > 0
            || minimumPdfTextCharacters < 0 || minimumPdfTextCharacters > 1000)
            throw new IllegalArgumentException("Document parsing exceeds the supported resource limits");
    }
    public static DocumentReadOptions defaults() {
        return new DocumentReadOptions(OcrMode.AUTO, "eng", 150, 50, 250_000,
            20L * 1024 * 1024, 16_000_000, Duration.ofSeconds(60), 20);
    }
}
