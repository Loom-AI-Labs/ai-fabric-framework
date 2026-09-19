package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainTargetInputMapper;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Objects;

final class DeclarativeSpecialistChainTargetInputMapper
    implements SpecialistChainTargetInputMapper<JsonNode, JsonNode> {

    private final SpecialistChainComponentId id;
    private final List<SpecialistChainManifest.MappingField> fields;
    private final SpecialistSchemaDefinition targetSchema;
    private final SpecialistJsonSchemaValidator schemaValidator;
    private final ObjectMapper objectMapper;
    private final BoundedJsonSupport json;
    private final SpecialistChainManifestMetrics metrics;

    DeclarativeSpecialistChainTargetInputMapper(
        SpecialistChainComponentId id,
        List<SpecialistChainManifest.MappingField> fields,
        SpecialistSchemaDefinition targetSchema,
        SpecialistJsonSchemaValidator schemaValidator,
        ObjectMapper objectMapper,
        BoundedJsonSupport json,
        SpecialistChainManifestMetrics metrics
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.fields = fields == null ? List.of() : List.copyOf(fields);
        this.targetSchema = Objects.requireNonNull(
            targetSchema,
            "targetSchema is required"
        );
        this.schemaValidator = Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        this.objectMapper = Objects.requireNonNull(
            objectMapper,
            "objectMapper is required"
        );
        this.json = Objects.requireNonNull(json, "json is required");
        this.metrics = Objects.requireNonNull(metrics, "metrics are required");
    }

    @Override
    public SpecialistChainComponentId id() {
        return id;
    }

    @Override
    public Class<JsonNode> chainRequestType() {
        return JsonNode.class;
    }

    @Override
    public Class<JsonNode> targetInputType() {
        return JsonNode.class;
    }

    @Override
    public JsonNode map(
        JsonNode chainRequest,
        SpecialistChainTargetRequest targetRequest
    ) {
        try {
            Objects.requireNonNull(chainRequest, "chainRequest is required");
            Objects.requireNonNull(targetRequest, "targetRequest is required");
            ObjectNode mapped = objectMapper.createObjectNode();
            BoundedJsonSupport.WorkBudget workBudget = json.newWorkBudget();
            for (SpecialistChainManifest.MappingField field : fields) {
                JsonNode selected;
                if (field.source()
                    == SpecialistChainMappingSource.MANAGER_OBJECTIVE) {
                    selected = objectMapper.getNodeFactory().textNode(
                        targetRequest.objective()
                    );
                    workBudget.consume(
                        json.inspect(selected, "manager objective"),
                        "manager objective"
                    );
                } else {
                    selected = json.select(
                        chainRequest,
                        field.sourcePointer(),
                        field.requiredOrDefault(),
                        "mapping.sourcePointer",
                        workBudget
                    );
                    if (selected == null) {
                        continue;
                    }
                }
                mapped.set(field.targetField(), selected);
            }
            json.validateMappedObject(mapped);
            schemaValidator.validate(targetSchema, mapped);
            metrics.recordMapping("mapped", "none");
            return mapped;
        } catch (RuntimeException ex) {
            metrics.recordMapping("rejected", "chain_input_mapping_failed");
            throw ex;
        }
    }
}
