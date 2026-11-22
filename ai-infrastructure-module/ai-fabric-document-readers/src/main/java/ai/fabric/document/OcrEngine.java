package ai.fabric.document;

import java.awt.image.BufferedImage;
import java.time.Duration;
import java.util.Set;

/** OCR implementations receive pixels, not source URLs, credentials or business tools. */
public interface OcrEngine {
    String id();
    Set<String> languages();
    String recognize(BufferedImage image, String language, Duration remaining, int maxCharacters);
}
