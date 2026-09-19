package ai.fabric.execution.chain;

import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Startup-validated chain definition and immutable content fingerprint. */
public record RegisteredSpecialistChain(
    SpecialistChainDefinition<?> definition,
    String contentHash,
    String managerContentHash,
    SpecialistChainDefinitionSource source,
    Optional<String> resourceHash,
    Optional<String> declarativeSemanticsHash,
    Map<SpecialistSchemaId, String> schemaDependencies,
    String sourceDescription
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public RegisteredSpecialistChain {
        Objects.requireNonNull(definition, "definition is required");
        contentHash = requireHash(contentHash, "contentHash");
        managerContentHash = requireHash(
            managerContentHash,
            "managerContentHash"
        );
        Objects.requireNonNull(source, "source is required");
        resourceHash = optionalHash(resourceHash, "resourceHash");
        declarativeSemanticsHash = optionalHash(
            declarativeSemanticsHash,
            "declarativeSemanticsHash"
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
        sourceDescription = Objects.requireNonNull(
            sourceDescription,
            "sourceDescription is required"
        ).trim();
        if (sourceDescription.isEmpty() || sourceDescription.length() > 240) {
            throw new IllegalArgumentException(
                "sourceDescription must contain between 1 and 240 characters"
            );
        }
        if (source == SpecialistChainDefinitionSource.MANIFEST
            && (resourceHash.isEmpty()
                || declarativeSemanticsHash.isEmpty()
                || schemaDependencies.isEmpty())) {
            throw new IllegalArgumentException(
                "Manifest chains require complete registration identity"
            );
        }
    }

    public RegisteredSpecialistChain(
        SpecialistChainDefinition<?> definition,
        String contentHash,
        String managerContentHash
    ) {
        this(
            definition,
            contentHash,
            managerContentHash,
            SpecialistChainDefinitionSource.JAVA,
            Optional.empty(),
            Optional.empty(),
            Map.of(),
            "java:" + definition.id()
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

    private static Optional<String> optionalHash(
        Optional<String> value,
        String field
    ) {
        if (value == null || value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(requireHash(value.get(), field));
    }
}
