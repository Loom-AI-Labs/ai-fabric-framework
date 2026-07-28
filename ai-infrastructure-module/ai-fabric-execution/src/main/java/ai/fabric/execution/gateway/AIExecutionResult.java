package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.action.ActionProposalView;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record AIExecutionResult<O>(
    String invocationId,
    SpecialistId specialistId,
    AIExecutionStatus status,
    O output,
    List<AIEvidenceReference> evidence,
    Map<String, Object> diagnostics,
    AIExecutionFailure failure,
    Instant startedAt,
    Instant completedAt,
    ActionProposalView actionProposal
) {
    public AIExecutionResult {
        invocationId = requireText(invocationId, "invocationId");
        Objects.requireNonNull(specialistId, "specialistId is required");
        Objects.requireNonNull(status, "status is required");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        diagnostics = diagnostics == null || diagnostics.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics));
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (status == AIExecutionStatus.SUCCEEDED && output == null) {
            throw new IllegalArgumentException("Successful execution requires output");
        }
        if (status == AIExecutionStatus.CONFIRMATION_REQUIRED
            && actionProposal == null) {
            throw new IllegalArgumentException(
                "Confirmation-required execution requires an action proposal"
            );
        }
        if (status == AIExecutionStatus.CONFIRMATION_REQUIRED
            && failure != null) {
            throw new IllegalArgumentException(
                "Confirmation-required execution is not a failure"
            );
        }
        if (status != AIExecutionStatus.SUCCEEDED
            && status != AIExecutionStatus.CONFIRMATION_REQUIRED
            && failure == null) {
            throw new IllegalArgumentException("Non-success execution requires failure");
        }
    }

    public AIExecutionResult(
        String invocationId,
        SpecialistId specialistId,
        AIExecutionStatus status,
        O output,
        List<AIEvidenceReference> evidence,
        Map<String, Object> diagnostics,
        AIExecutionFailure failure,
        Instant startedAt,
        Instant completedAt
    ) {
        this(
            invocationId,
            specialistId,
            status,
            output,
            evidence,
            diagnostics,
            failure,
            startedAt,
            completedAt,
            null
        );
    }

    public boolean succeeded() {
        return status == AIExecutionStatus.SUCCEEDED;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field + " is required").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
