package ai.fabric.execution.specialist.manifest;

import com.fasterxml.jackson.databind.JsonNode;

public record SpecialistSchemaSpec(
    SpecialistSchemaDirection direction,
    String draft,
    JsonNode schema
) {
    public SpecialistSchemaSpec {
        schema = schema == null ? null : schema.deepCopy();
    }

    @Override
    public JsonNode schema() {
        return schema == null ? null : schema.deepCopy();
    }
}
