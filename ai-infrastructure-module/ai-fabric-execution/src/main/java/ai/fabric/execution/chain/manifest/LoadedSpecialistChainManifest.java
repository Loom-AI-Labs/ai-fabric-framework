package ai.fabric.execution.chain.manifest;

import java.util.Objects;

/** Parsed chain resource with bounded provenance and audit identity. */
public record LoadedSpecialistChainManifest(
    SpecialistChainManifest manifest,
    String resourceHash,
    String source
) {
    public LoadedSpecialistChainManifest {
        Objects.requireNonNull(manifest, "manifest is required");
        resourceHash = requireHash(resourceHash);
        source = requireText(source, "source");
    }

    private static String requireHash(String value) {
        String normalized = requireText(value, "resourceHash");
        if (!normalized.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException(
                "resourceHash must be a lowercase SHA-256 value"
            );
        }
        return normalized;
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
