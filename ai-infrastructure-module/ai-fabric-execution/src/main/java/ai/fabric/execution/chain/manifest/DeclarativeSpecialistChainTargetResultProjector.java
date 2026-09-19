package ai.fabric.execution.chain.manifest;

import ai.fabric.execution.chain.SpecialistChainComponentId;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainTargetResultProjector;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.specialist.manifest.SpecialistJsonSchemaValidator;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class DeclarativeSpecialistChainTargetResultProjector
    implements SpecialistChainTargetResultProjector<JsonNode, JsonNode> {

    private final SpecialistChainComponentId id;
    private final SpecialistSchemaDefinition outputSchema;
    private final SpecialistJsonSchemaValidator schemaValidator;
    private final BoundedJsonSupport json;
    private final String summaryPointer;
    private final List<SpecialistChainManifest.FactField> facts;
    private final SpecialistChainEvidencePolicy evidencePolicy;
    private final SpecialistChainManifestMetrics metrics;

    DeclarativeSpecialistChainTargetResultProjector(
        SpecialistChainComponentId id,
        SpecialistSchemaDefinition outputSchema,
        SpecialistJsonSchemaValidator schemaValidator,
        BoundedJsonSupport json,
        String summaryPointer,
        List<SpecialistChainManifest.FactField> facts,
        SpecialistChainEvidencePolicy evidencePolicy,
        SpecialistChainManifestMetrics metrics
    ) {
        this.id = Objects.requireNonNull(id, "id is required");
        this.outputSchema = Objects.requireNonNull(
            outputSchema,
            "outputSchema is required"
        );
        this.schemaValidator = Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        this.json = Objects.requireNonNull(json, "json is required");
        this.summaryPointer = json.validatePointer(
            summaryPointer,
            "summaryPointer"
        );
        this.facts = facts == null ? List.of() : List.copyOf(facts);
        this.evidencePolicy = Objects.requireNonNull(
            evidencePolicy,
            "evidencePolicy is required"
        );
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
    public Class<JsonNode> targetOutputType() {
        return JsonNode.class;
    }

    @Override
    public SpecialistChainResultProjection project(
        JsonNode chainRequest,
        AIExecutionResult<JsonNode> targetExecution
    ) {
        try {
            Objects.requireNonNull(chainRequest, "chainRequest is required");
            Objects.requireNonNull(
                targetExecution,
                "targetExecution is required"
            );
            if (!targetExecution.succeeded()) {
                throw new IllegalArgumentException(
                    "Only successful worker results may be projected"
                );
            }
            JsonNode output = Objects.requireNonNull(
                targetExecution.output(),
                "worker output is required"
            );
            schemaValidator.validate(outputSchema, output);
            BoundedJsonSupport.WorkBudget workBudget = json.newWorkBudget();
            JsonNode summary = json.select(
                output,
                summaryPointer,
                true,
                "summaryPointer",
                workBudget
            );
            if (!summary.isTextual()) {
                throw new IllegalArgumentException(
                    "summaryPointer must select a string"
                );
            }
            Map<String, String> projectedFacts = new LinkedHashMap<>();
            for (SpecialistChainManifest.FactField fact : facts) {
                JsonNode selected = json.select(
                    output,
                    fact.valuePointer(),
                    fact.requiredOrDefault(),
                    "fact.valuePointer",
                    workBudget
                );
                if (selected == null) {
                    continue;
                }
                if (!selected.isTextual()) {
                    throw new IllegalArgumentException(
                        "Fact pointers must select strings"
                    );
                }
                projectedFacts.put(fact.name(), selected.textValue());
            }
            List<String> evidence = new ArrayList<>();
            if (evidencePolicy == SpecialistChainEvidencePolicy.ALL_APPROVED) {
                targetExecution.evidence().forEach(reference ->
                    evidence.add(reference.evidenceId())
                );
            }
            SpecialistChainResultProjection projection =
                new SpecialistChainResultProjection(
                    summary.textValue(),
                    projectedFacts,
                    evidence
                );
            metrics.recordProjection("projected", "none");
            return projection;
        } catch (RuntimeException ex) {
            metrics.recordProjection(
                "rejected",
                "chain_result_projection_failed"
            );
            throw ex;
        }
    }
}
