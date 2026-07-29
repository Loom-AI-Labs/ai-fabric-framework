package ai.fabric.execution.specialist.manifest;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Draft 2020-12 validation with external references disabled.
 */
public final class SpecialistJsonSchemaValidator {

    private static final String DRAFT_2020_12 = "2020-12";

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(
        SpecificationVersion.DRAFT_2020_12
    );
    private final ConcurrentMap<String, Schema> schemas =
        new ConcurrentHashMap<>();

    public void validateDefinition(
        SpecialistSchemaDefinition definition,
        String source
    ) {
        Objects.requireNonNull(definition, "definition is required");
        if (definition.spec() == null || definition.spec().schema() == null) {
            throw failure(
                "SCHEMA_DOCUMENT_REQUIRED",
                "Specialist schema content is required.",
                source
            );
        }
        if (!DRAFT_2020_12.equals(definition.spec().draft())) {
            throw failure(
                "SCHEMA_DRAFT_UNSUPPORTED",
                "Only JSON Schema Draft 2020-12 is supported.",
                source
            );
        }
        rejectExternalReferences(definition.spec().schema(), source);
        try {
            Schema metaSchema = registry.getSchema(
                SchemaLocation.of(
                    SpecificationVersion.DRAFT_2020_12.getDialectId()
                )
            );
            List<Error> metaErrors = metaSchema.validate(
                definition.spec().schema()
            );
            if (!metaErrors.isEmpty()) {
                throw failure(
                    "SCHEMA_DEFINITION_INVALID",
                    "The specialist JSON Schema is invalid at "
                        + safeLocation(metaErrors.getFirst()) + ".",
                    source
                );
            }
            compiled(definition);
        } catch (SpecialistManifestException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new SpecialistManifestException(
                "SCHEMA_DEFINITION_INVALID",
                "The specialist JSON Schema could not be compiled.",
                source,
                ex
            );
        }
    }

    public void validate(
        SpecialistSchemaDefinition definition,
        JsonNode value
    ) {
        Objects.requireNonNull(definition, "definition is required");
        Objects.requireNonNull(value, "value is required");
        List<Error> errors = compiled(definition).validate(value);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                "JSON does not satisfy schema " + definition.id()
                    + " at " + safeLocation(errors.getFirst())
            );
        }
    }

    private Schema compiled(SpecialistSchemaDefinition definition) {
        return schemas.computeIfAbsent(
            definition.id().toString(),
            ignored -> registry.getSchema(definition.spec().schema())
        );
    }

    private void rejectExternalReferences(JsonNode node, String source) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            JsonNode reference = node.get("$ref");
            if (reference != null
                && (!reference.isTextual()
                    || !reference.textValue().startsWith("#"))) {
                throw failure(
                    "SCHEMA_EXTERNAL_REFERENCE_FORBIDDEN",
                    "Specialist schemas may use only local fragment references.",
                    source
                );
            }
            node.elements().forEachRemaining(child ->
                rejectExternalReferences(child, source)
            );
            return;
        }
        if (node.isArray()) {
            node.elements().forEachRemaining(child ->
                rejectExternalReferences(child, source)
            );
        }
    }

    private String safeLocation(Error error) {
        return error != null && error.getInstanceLocation() != null
            ? error.getInstanceLocation().toString()
            : "/";
    }

    private SpecialistManifestException failure(
        String reason,
        String message,
        String source
    ) {
        return new SpecialistManifestException(reason, message, source);
    }
}
