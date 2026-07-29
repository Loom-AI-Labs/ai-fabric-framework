package ai.fabric.execution.specialist.manifest;

import java.util.Objects;

public record LoadedSpecialistManifest(
    SpecialistManifest manifest,
    String contentHash,
    String source
) {
    public LoadedSpecialistManifest {
        Objects.requireNonNull(manifest, "manifest is required");
        contentHash = requireText(contentHash, "contentHash");
        source = requireText(source, "source");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
