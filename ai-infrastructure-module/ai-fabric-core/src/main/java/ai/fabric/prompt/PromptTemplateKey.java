package ai.fabric.prompt;

import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * Identifies a versioned prompt template stored on the classpath.
 */
public record PromptTemplateKey(
    String family,
    String version,
    String name
) {

    public PromptTemplateKey {
        family = normalizeFamily(family);
        version = normalizeSegment(version, "version");
        name = normalizeSegment(name, "name");

        Objects.requireNonNull(family, "family must not be null/blank");
        Objects.requireNonNull(version, "version must not be null/blank");
        Objects.requireNonNull(name, "name must not be null/blank");
    }

    public String id() {
        return family + ":" + version + ":" + name;
    }

    public String resourcePath() {
        return "prompts/" + family + "/" + version + "/" + name + ".md";
    }

    private static String normalizeFamily(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        trimmed = trimmed.replaceAll("^/+", "");
        trimmed = trimmed.replaceAll("/+$", "");
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed;
    }

    private static String normalizeSegment(String raw, String label) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.contains("/") || trimmed.contains("\\")) {
            throw new IllegalArgumentException(label + " must not contain path separators");
        }
        return trimmed;
    }
}

