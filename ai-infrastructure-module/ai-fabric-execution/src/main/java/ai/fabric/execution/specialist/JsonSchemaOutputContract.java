package ai.fabric.execution.specialist;

import ai.fabric.execution.specialist.manifest.SpecialistSchemaId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Exact-version JSON Schema output contract for a manifest specialist.
 */
public record JsonSchemaOutputContract(
    SpecialistSchemaId schemaId,
    JsonNode schema,
    String promptInstructions
) implements SpecialistOutputContract {

    public JsonSchemaOutputContract {
        Objects.requireNonNull(schemaId, "schemaId is required");
        Objects.requireNonNull(schema, "schema is required");
        schema = schema.deepCopy();
        promptInstructions = requireText(
            promptInstructions,
            "promptInstructions"
        );
    }

    @Override
    public JsonNode schema() {
        return schema.deepCopy();
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
