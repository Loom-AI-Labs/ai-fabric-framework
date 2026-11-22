package ai.fabric.document;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;

/** Local Tesseract CLI adapter. Command shape and executable are application-owned. */
public final class TesseractOcrEngine implements OcrEngine {
    private final Path executable;
    private final Set<String> languages;
    public TesseractOcrEngine(Path executable, Set<String> languages) {
        if (executable == null || !executable.isAbsolute() || languages == null || languages.isEmpty()
            || languages.stream().anyMatch(value -> !value.matches("[a-z]{3}")))
            throw new IllegalArgumentException("A server-owned executable and installed languages are required");
        this.executable = executable; this.languages = Set.copyOf(languages);
    }
    @Override public String id() { return "tesseract-local"; }
    @Override public Set<String> languages() { return languages; }
    @Override public String recognize(BufferedImage image, String language, Duration remaining, int maxCharacters) {
        if (!Files.isExecutable(executable)) throw new DocumentReadException("OCR_UNAVAILABLE", "Local OCR is not installed on this runtime.");
        if (remaining == null || remaining.isNegative() || remaining.isZero() || maxCharacters < 1)
            throw new DocumentReadException("OCR_LIMIT_EXCEEDED", "OCR has no remaining resource allowance.");
        for (String part : language.split("\\+")) if (!languages.contains(part))
            throw new DocumentReadException("OCR_LANGUAGE_UNAVAILABLE", "The selected OCR language is not installed.");
        Path directory = null;
        Process process = null;
        long deadline = System.nanoTime() + remaining.toNanos();
        try {
            directory = Files.createTempDirectory("ai-fabric-ocr-");
            Path input = directory.resolve("page.png"), output = directory.resolve("result"), errors = directory.resolve("diagnostic");
            ImageIO.write(image, "png", input.toFile());
            process = new ProcessBuilder(executable.toString(), input.toString(), output.toString(), "-l", language,
                "--psm", "3", "txt").redirectError(errors.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (System.nanoTime() >= deadline) throw new DocumentReadException("OCR_TIMEOUT", "OCR exceeded its time allowance.");
            Path text = directory.resolve("result.txt");
            while (!process.waitFor(Math.min(100, Math.max(1, remaining.toMillis())), TimeUnit.MILLISECONDS)) {
                if (Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
                    throw new DocumentReadException("OCR_TIMEOUT", "OCR exceeded its time allowance.");
                if ((Files.exists(text) && Files.size(text) > (long) maxCharacters * 4)
                    || (Files.exists(errors) && Files.size(errors) > 65_536))
                    throw new DocumentReadException("OCR_OUTPUT_LIMIT", "OCR output exceeded its allowance.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(text))
                throw new DocumentReadException("OCR_FAILED", "OCR could not read this page. Review the source or language.");
            if (Files.size(text) > (long) maxCharacters * 4) throw new DocumentReadException("OCR_OUTPUT_LIMIT", "OCR output exceeded its allowance.");
            String value = Files.readString(text, StandardCharsets.UTF_8);
            if (value.length() > maxCharacters) throw new DocumentReadException("OCR_OUTPUT_LIMIT", "OCR output exceeded its allowance.");
            return value;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new DocumentReadException("OCR_CANCELLED", "OCR was cancelled.", exception);
        } catch (IOException exception) {
            throw new DocumentReadException("OCR_FAILED", "OCR could not complete on this runtime.", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                try { process.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            if (directory != null) try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            } catch (IOException ignored) { /* A caller-owned temp root can be swept separately. */ }
        }
    }
}
