package ai.fabric.execution.specialist.manifest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class SpecialistJsonSchemaRegistry {

    private final Map<SpecialistSchemaId, SpecialistSchemaDefinition> schemas;

    public SpecialistJsonSchemaRegistry(
        List<SpecialistSchemaDefinition> definitions,
        SpecialistJsonSchemaValidator validator
    ) {
        Objects.requireNonNull(validator, "validator is required");
        Map<SpecialistSchemaId, SpecialistSchemaDefinition> loaded =
            new LinkedHashMap<>();
        for (SpecialistSchemaDefinition definition :
            definitions == null
                ? List.<SpecialistSchemaDefinition>of()
                : definitions) {
            Objects.requireNonNull(
                definition,
                "schema definition must not be null"
            );
            validateEnvelope(definition);
            validator.validateDefinition(
                definition,
                "schema:" + definition.id()
            );
            if (loaded.putIfAbsent(definition.id(), definition) != null) {
                throw new SpecialistManifestException(
                    "DUPLICATE_SCHEMA_ID",
                    "Duplicate specialist schema " + definition.id() + ".",
                    "schema:" + definition.id()
                );
            }
        }
        this.schemas = Map.copyOf(loaded);
    }

    public Optional<SpecialistSchemaDefinition> find(SpecialistSchemaId id) {
        return Optional.ofNullable(schemas.get(id));
    }

    public SpecialistSchemaDefinition require(
        SpecialistSchemaId id,
        SpecialistSchemaDirection direction
    ) {
        SpecialistSchemaDefinition definition = find(id).orElseThrow(() ->
            new SpecialistManifestException(
                "SCHEMA_REFERENCE_NOT_FOUND",
                "No specialist schema is registered for " + id + ".",
                "schema:" + id
            )
        );
        if (definition.spec().direction() != direction) {
            throw new SpecialistManifestException(
                "SCHEMA_DIRECTION_MISMATCH",
                "Schema " + id + " is not an " + direction + " schema.",
                "schema:" + id
            );
        }
        return definition;
    }

    public List<SpecialistSchemaDefinition> list() {
        return List.copyOf(schemas.values());
    }

    private void validateEnvelope(SpecialistSchemaDefinition definition) {
        if (!"ai.fabric/v1".equals(definition.apiVersion())) {
            throw new SpecialistManifestException(
                "RESOURCE_API_VERSION_UNSUPPORTED",
                "Only ai.fabric/v1 specialist resources are supported.",
                "schema"
            );
        }
        if (!"SpecialistSchema".equals(definition.kind())) {
            throw new SpecialistManifestException(
                "RESOURCE_KIND_INVALID",
                "Schema resources must use kind SpecialistSchema.",
                "schema"
            );
        }
        if (definition.metadata() == null || definition.spec() == null) {
            throw new SpecialistManifestException(
                "SCHEMA_RESOURCE_INCOMPLETE",
                "Specialist schema metadata and spec are required.",
                "schema"
            );
        }
        Objects.requireNonNull(
            definition.spec().direction(),
            "schema direction is required"
        );
    }
}
