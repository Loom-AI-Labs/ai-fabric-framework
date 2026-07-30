package ai.fabric.execution.specialist.manifest;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.specialist.JsonSchemaOutputContract;
import ai.fabric.execution.specialist.SpecialistOutputAdapter;
import ai.fabric.execution.specialist.SpecialistOutputContract;
import ai.fabric.execution.specialist.SpecialistOutputMode;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.capability.RequestedCapabilityProfile;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Objects;

public final class JsonSchemaSpecialistOutputAdapter
    implements SpecialistOutputAdapter<JsonNode> {

    private final SpecialistSchemaDefinition schema;
    private final SpecialistOutputSpec specification;
    private final SpecialistGroundingSpec grounding;
    private final RequestedCapabilityProfile capabilities;
    private final String outputContractInstructions;
    private final SpecialistJsonSchemaValidator schemaValidator;
    private final CanonicalJsonSupport canonicalJson;
    private final DefaultManifestGroundingValidator defaultGroundingValidator;
    private final List<SpecialistGroundingValidator> groundingValidators;
    private final List<SpecialistFinalOutputValidator> finalValidators;
    private final SpecialistDirectOutputProjector directProjector;
    private final SpecialistOutputNormalizer normalizer;

    public JsonSchemaSpecialistOutputAdapter(
        SpecialistSchemaDefinition schema,
        SpecialistOutputSpec specification,
        SpecialistGroundingSpec grounding,
        RequestedCapabilityProfile capabilities,
        String outputContractInstructions,
        SpecialistJsonSchemaValidator schemaValidator,
        CanonicalJsonSupport canonicalJson,
        DefaultManifestGroundingValidator defaultGroundingValidator,
        List<SpecialistGroundingValidator> groundingValidators,
        List<SpecialistFinalOutputValidator> finalValidators,
        SpecialistDirectOutputProjector directProjector,
        SpecialistOutputNormalizer normalizer
    ) {
        this.schema = Objects.requireNonNull(schema, "schema is required");
        this.specification = Objects.requireNonNull(
            specification,
            "specification is required"
        );
        this.grounding = Objects.requireNonNull(
            grounding,
            "grounding is required"
        );
        this.capabilities = Objects.requireNonNull(
            capabilities,
            "capabilities is required"
        );
        this.outputContractInstructions = requireText(
            outputContractInstructions,
            "outputContractInstructions"
        );
        this.schemaValidator = Objects.requireNonNull(
            schemaValidator,
            "schemaValidator is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.defaultGroundingValidator = Objects.requireNonNull(
            defaultGroundingValidator,
            "defaultGroundingValidator is required"
        );
        this.groundingValidators = groundingValidators == null
            ? List.of()
            : List.copyOf(groundingValidators);
        this.finalValidators = finalValidators == null
            ? List.of()
            : List.copyOf(finalValidators);
        this.directProjector = directProjector;
        this.normalizer = normalizer;
    }

    @Override
    public Class<JsonNode> outputType() {
        return JsonNode.class;
    }

    @Override
    public SpecialistOutputMode outputMode() {
        return specification.mode();
    }

    @Override
    public OrchestrationIntentPolicy orchestrationIntentPolicy() {
        boolean noActions = capabilities.visibleActions().isEmpty()
            && capabilities.requestableReadActions().isEmpty()
            && capabilities.proposableWriteActions().isEmpty();
        boolean structuredOutputOnly =
            specification.mode() == SpecialistOutputMode.STRUCTURED_GENERATION
                && grounding.requirement()
                    == SpecialistGroundingRequirement.NONE
                && !capabilities.retrievalEnabled()
                && noActions;
        return structuredOutputOnly
            ? OrchestrationIntentPolicy.STRUCTURED_OUTPUT_ONLY
            : OrchestrationIntentPolicy.MODEL_DIRECTED;
    }

    @Override
    public String outputContractInstructions() {
        return outputContractInstructions;
    }

    @Override
    public SpecialistOutputContract outputContract() {
        return new JsonSchemaOutputContract(
            schema.id(),
            schema.spec().schema(),
            outputContractInstructions
        );
    }

    @Override
    public void validateGrounding(
        OrchestrationResult result,
        List<AIEvidenceReference> evidence
    ) {
        SpecialistGroundingValidationContext context =
            new SpecialistGroundingValidationContext(
                result,
                evidence,
                grounding,
                capabilities
            );
        defaultGroundingValidator.validate(context);
        groundingValidators.forEach(validator -> validator.validate(context));
    }

    @Override
    public JsonNode project(
        OrchestrationResult result,
        List<AIEvidenceReference> evidence
    ) {
        if (directProjector == null) {
            throw new IllegalArgumentException(
                "DIRECT_PROJECTION requires a registered projector"
            );
        }
        JsonNode projected = directProjector.project(result, evidence);
        return projected == null ? null : projected.deepCopy();
    }

    @Override
    public void validate(JsonNode output) {
        schemaValidator.validate(schema, output);
    }

    @Override
    public void validateFinalOutput(
        JsonNode output,
        OrchestrationResult sourceResult,
        List<AIEvidenceReference> evidence
    ) {
        validate(output);
        SpecialistFinalOutputValidationContext context =
            new SpecialistFinalOutputValidationContext(
                output,
                sourceResult,
                evidence
            );
        finalValidators.forEach(validator -> validator.validate(context));
    }

    @Override
    public JsonNode normalizeFinalOutput(
        JsonNode output,
        OrchestrationResult sourceResult,
        List<AIEvidenceReference> evidence
    ) {
        if (normalizer == null) {
            return output.deepCopy();
        }
        JsonNode normalized = normalizer.normalize(
            new SpecialistOutputNormalizationContext(
                output,
                sourceResult,
                evidence
            )
        );
        if (normalized == null) {
            throw new IllegalArgumentException(
                "Output normalizer returned no output"
            );
        }
        return normalized.deepCopy();
    }

    @Override
    public String conversationOutput(
        JsonNode output,
        OrchestrationResult sourceResult
    ) {
        if (specification.conversationTextPointer() == null) {
            return null;
        }
        JsonNode selected = output.at(specification.conversationTextPointer());
        if (!selected.isTextual() || selected.textValue().isBlank()) {
            throw new IllegalArgumentException(
                "conversationTextPointer must select a non-blank string"
            );
        }
        return selected.textValue().trim();
    }

    @Override
    public int serializedOutputCharacters(JsonNode output) {
        return canonicalJson.write(output).length();
    }

    private String requireText(String value, String field) {
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
