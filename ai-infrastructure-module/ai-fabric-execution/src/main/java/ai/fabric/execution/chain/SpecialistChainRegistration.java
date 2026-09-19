package ai.fabric.execution.chain;

import java.util.Objects;

/** One source-aware chain registration awaiting registry validation. */
public record SpecialistChainRegistration(
    SpecialistChainDefinition<?> definition,
    SpecialistChainDefinitionSource source,
    SpecialistChainRegistrationIdentity identity
) {
    public SpecialistChainRegistration {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(source, "source is required");
        Objects.requireNonNull(identity, "identity is required");
        boolean hasManifestIdentity = identity.resourceHash().isPresent()
            && identity.declarativeSemanticsHash().isPresent()
            && !identity.schemaDependencies().isEmpty();
        if (source == SpecialistChainDefinitionSource.MANIFEST
            && !hasManifestIdentity) {
            throw new IllegalArgumentException(
                "Manifest chain registrations require full manifest identity"
            );
        }
        if (source == SpecialistChainDefinitionSource.JAVA
            && (identity.resourceHash().isPresent()
                || identity.declarativeSemanticsHash().isPresent()
                || !identity.schemaDependencies().isEmpty())) {
            throw new IllegalArgumentException(
                "Java chain registrations cannot carry manifest identity"
            );
        }
    }

    public static SpecialistChainRegistration javaDefinition(
        SpecialistChainDefinition<?> definition
    ) {
        return new SpecialistChainRegistration(
            definition,
            SpecialistChainDefinitionSource.JAVA,
            SpecialistChainRegistrationIdentity.javaDefinition()
        );
    }
}
