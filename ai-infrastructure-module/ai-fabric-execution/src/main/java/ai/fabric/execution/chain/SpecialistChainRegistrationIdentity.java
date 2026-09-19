package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Audit and semantic identity supplied by a chain-definition source. */
public record SpecialistChainRegistrationIdentity(
    Optional<String> resourceHash,
    Optional<String> declarativeSemanticsHash,
    Map<SpecialistSchemaId, String> schemaDependencies,
    String safeSource
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public SpecialistChainRegistrationIdentity {
        resourceHash = resourceHash == null ? Optional.empty() : resourceHash;
        declarativeSemanticsHash = declarativeSemanticsHash == null
            ? Optional.empty()
            : declarativeSemanticsHash;
        resourceHash.ifPresent(value -> requireHash(value, "resourceHash"));
        declarativeSemanticsHash.ifPresent(value ->
            requireHash(value, "declarativeSemanticsHash")
        );
        LinkedHashMap<SpecialistSchemaId, String> dependencies =
            new LinkedHashMap<>();
        if (schemaDependencies != null) {
            schemaDependencies.forEach((id, hash) -> dependencies.put(
                Objects.requireNonNull(id, "schema dependency ID is required"),
                requireHash(hash, "schema dependency hash")
            ));
        }
        schemaDependencies = Map.copyOf(dependencies);
        safeSource = requireText(safeSource, "safeSource");
    }

    public static SpecialistChainRegistrationIdentity javaDefinition() {
        return new SpecialistChainRegistrationIdentity(
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            "java-definition"
        );
    }

    public static SpecialistChainRegistrationIdentity manifest(
        String resourceHash,
        String declarativeSemanticsHash,
        Map<SpecialistSchemaId, String> schemaDependencies,
        String safeSource
    ) {
        if (schemaDependencies == null || schemaDependencies.isEmpty()) {
            throw new IllegalArgumentException(
                "Manifest chains require schema dependencies"
            );
        }
        return new SpecialistChainRegistrationIdentity(
            Optional.of(requireHash(resourceHash, "resourceHash")),
            Optional.of(requireHash(
                declarativeSemanticsHash,
                "declarativeSemanticsHash"
            )),
            schemaDependencies,
            safeSource
        );
    }

    private static String requireHash(String value, String field) {
        String normalized = requireText(value, field);
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                field + " must be a lowercase SHA-256 value"
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
