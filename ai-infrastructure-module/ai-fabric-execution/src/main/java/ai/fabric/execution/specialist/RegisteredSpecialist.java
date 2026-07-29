package ai.fabric.execution.specialist;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Executable definition plus bounded operational provenance.
 */
public record RegisteredSpecialist(
    SpecialistDefinition<?, ?> definition,
    SpecialistDefinitionSource source,
    String contentHash,
    String sourceDescription,
    Map<String, String> labels
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public RegisteredSpecialist {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(source, "source is required");
        contentHash = requireHash(contentHash);
        sourceDescription = requireText(
            sourceDescription,
            "sourceDescription",
            240
        );
        labels = labels == null || labels.isEmpty()
            ? Map.of()
            : Map.copyOf(new LinkedHashMap<>(labels));
    }

    public SpecialistId id() {
        return definition.id();
    }

    public static RegisteredSpecialist javaDefinition(
        SpecialistDefinition<?, ?> definition
    ) {
        Objects.requireNonNull(definition, "definition is required");
        return new RegisteredSpecialist(
            definition,
            SpecialistDefinitionSource.JAVA,
            SpecialistDefinitionFingerprinter.fingerprint(definition),
            "java:" + definition.id(),
            Map.of()
        );
    }

    private static String requireHash(String value) {
        String normalized = requireText(value, "contentHash", 64);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "contentHash must be a lowercase SHA-256 value"
            );
        }
        return normalized;
    }

    private static String requireText(
        String value,
        String field,
        int maxLength
    ) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }
}
