package ai.fabric.execution.specialist.manifest;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.intent.orchestration.OrchestrationResult;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generic grounding checks over approved evidence and read-action observations.
 */
public final class DefaultManifestGroundingValidator {

    public void validate(SpecialistGroundingValidationContext context) {
        SpecialistGroundingSpec specification = context.specification();
        if (specification.requirement()
            == SpecialistGroundingRequirement.NONE) {
            return;
        }
        boolean capabilityUsed =
            context.capabilities().retrievalEnabled()
                || !context.capabilities().requestableReadActions().isEmpty();
        if (specification.requirement()
                == SpecialistGroundingRequirement.WHEN_CAPABILITY_USED
            && !capabilityUsed) {
            return;
        }
        if (specification.requireEvidenceCitations()
            && context.evidence().isEmpty()) {
            throw new IllegalArgumentException(
                "Grounding requires at least one cited evidence reference"
            );
        }
        for (SpecialistGroundingSourceSpec source : specification.sources()) {
            validateSource(source, context);
        }
    }

    private void validateSource(
        SpecialistGroundingSourceSpec source,
        SpecialistGroundingValidationContext context
    ) {
        int actual = switch (source.type()) {
            case VECTOR_SPACE -> vectorEvidence(
                context.evidence(),
                source.name()
            ).size();
            case ANY_ALLOWED_VECTOR_SPACE -> boundedCount(
                context.evidence().stream()
                    .filter(reference ->
                        reference != null
                            && reference.vectorSpace() != null
                            && context.capabilities()
                                .requestedVectorSpaces()
                                .contains(reference.vectorSpace())
                    )
                    .count()
            );
            case READ_ACTION -> readActionObservations(
                context.result(),
                source.name(),
                source.groundingUsable()
            );
        };
        if (actual < source.minimumCount()) {
            throw new IllegalArgumentException(
                "Grounding source " + source.type()
                    + " did not satisfy its minimum observation count"
            );
        }
        if (!source.requiredEvidenceIds().isEmpty()) {
            Set<String> available = vectorEvidence(
                context.evidence(),
                source.name()
            ).stream()
                .map(AIEvidenceReference::evidenceId)
                .collect(java.util.stream.Collectors.toSet());
            if (!available.containsAll(source.requiredEvidenceIds())) {
                throw new IllegalArgumentException(
                    "Grounding source is missing required evidence IDs"
                );
            }
        }
    }

    private int boundedCount(long count) {
        return count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
    }

    private List<AIEvidenceReference> vectorEvidence(
        List<AIEvidenceReference> evidence,
        String vectorSpace
    ) {
        if (evidence == null || evidence.isEmpty()) {
            return List.of();
        }
        return evidence.stream()
            .filter(reference ->
                reference != null
                    && vectorSpace != null
                    && vectorSpace.equals(reference.vectorSpace())
            )
            .toList();
    }

    private int readActionObservations(
        OrchestrationResult result,
        String actionName,
        boolean requireGroundingUsable
    ) {
        List<Map<?, ?>> actions = new ArrayList<>();
        collectReadActions(
            result,
            actions,
            Collections.newSetFromMap(new IdentityHashMap<>())
        );
        return (int) actions.stream()
            .filter(action -> actionName.equals(action.get("action")))
            .filter(action ->
                !requireGroundingUsable
                    || Boolean.TRUE.equals(action.get("groundingUsable"))
            )
            .count();
    }

    private void collectReadActions(
        OrchestrationResult result,
        List<Map<?, ?>> actions,
        Set<OrchestrationResult> visited
    ) {
        if (result == null || !visited.add(result)) {
            return;
        }
        Map<String, Object> data = result.getData();
        if (data != null) {
            Object rawResolution = data.get("readActionResolution");
            if (rawResolution instanceof Map<?, ?> resolution
                && resolution.get("executedActions") instanceof List<?> raw) {
                raw.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .forEach(actions::add);
            }
        }
        if (result.getChildren() != null) {
            result.getChildren().forEach(child ->
                collectReadActions(child, actions, visited)
            );
        }
    }
}
