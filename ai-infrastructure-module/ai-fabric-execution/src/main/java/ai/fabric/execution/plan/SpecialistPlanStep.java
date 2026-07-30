package ai.fabric.execution.plan;

import ai.fabric.execution.specialist.SpecialistId;
import java.util.Objects;

/**
 * One fixed specialist invocation in an immutable plan.
 */
public record SpecialistPlanStep(
    String id,
    SpecialistId specialistId,
    Class<?> inputType,
    Class<?> outputType,
    PlanComponentId inputMapperId
) implements PlanStage {
    public SpecialistPlanStep {
        id = requireText(id, "id");
        Objects.requireNonNull(specialistId, "specialistId is required");
        Objects.requireNonNull(inputType, "inputType is required");
        Objects.requireNonNull(outputType, "outputType is required");
        Objects.requireNonNull(inputMapperId, "inputMapperId is required");
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
