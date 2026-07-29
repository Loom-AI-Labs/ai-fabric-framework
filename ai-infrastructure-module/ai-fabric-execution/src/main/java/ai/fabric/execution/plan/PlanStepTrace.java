package ai.fabric.execution.plan;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Safe execution trace for one independently governed specialist step.
 */
public record PlanStepTrace(
    String stepId,
    SpecialistId specialistId,
    String invocationId,
    AIExecutionStatus status,
    List<AIEvidenceReference> evidence,
    Instant startedAt,
    Instant completedAt
) {
    public PlanStepTrace {
        stepId = requireText(stepId, "stepId");
        Objects.requireNonNull(specialistId, "specialistId is required");
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(status, "status is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
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
