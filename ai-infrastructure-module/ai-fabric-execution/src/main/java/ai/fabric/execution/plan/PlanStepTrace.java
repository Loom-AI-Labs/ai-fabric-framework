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
    String parallelGroupId,
    String sourceRevision,
    SpecialistId specialistId,
    String invocationId,
    AIExecutionStatus status,
    List<AIEvidenceReference> evidence,
    Instant startedAt,
    Instant completedAt
) {
    public PlanStepTrace {
        stepId = requireText(stepId, "stepId");
        parallelGroupId = normalizeOptional(parallelGroupId);
        sourceRevision = normalizeOptional(sourceRevision);
        Objects.requireNonNull(specialistId, "specialistId is required");
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(status, "status is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
    }

    public PlanStepTrace(
        String stepId,
        SpecialistId specialistId,
        String invocationId,
        AIExecutionStatus status,
        List<AIEvidenceReference> evidence,
        Instant startedAt,
        Instant completedAt
    ) {
        this(
            stepId,
            null,
            null,
            specialistId,
            invocationId,
            status,
            evidence,
            startedAt,
            completedAt
        );
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

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
