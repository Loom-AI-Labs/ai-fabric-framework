package ai.fabric.execution.chain;

import java.util.Objects;
import java.util.regex.Pattern;

/** Startup-validated chain definition and immutable content fingerprint. */
public record RegisteredSpecialistChain(
    SpecialistChainDefinition<?> definition,
    String contentHash,
    String managerContentHash
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public RegisteredSpecialistChain {
        Objects.requireNonNull(definition, "definition is required");
        contentHash = requireHash(contentHash, "contentHash");
        managerContentHash = requireHash(
            managerContentHash,
            "managerContentHash"
        );
    }

    public SpecialistChainId id() {
        return definition.id();
    }

    private static String requireHash(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 value"
            );
        }
        return normalized;
    }
}
