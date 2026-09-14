package ai.fabric.execution.chain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Safe current state returned by access-scoped execution lookup. */
public record SpecialistChainExecutionSnapshot(
    String executionId,
    SpecialistChainId chainId,
    SpecialistChainExecutionStatus status,
    int nextDecisionIndex,
    List<SpecialistChainStepTrace> steps,
    SpecialistChainFailure failure,
    boolean durable,
    Instant startedAt,
    Instant updatedAt,
    Instant deadline
) {
    public SpecialistChainExecutionSnapshot {
        executionId = Objects.requireNonNull(
            executionId,
            "executionId is required"
        ).trim();
        if (executionId.isEmpty()) {
            throw new IllegalArgumentException("executionId is required");
        }
        Objects.requireNonNull(chainId, "chainId is required");
        Objects.requireNonNull(status, "status is required");
        if (nextDecisionIndex < 0) {
            throw new IllegalArgumentException(
                "nextDecisionIndex cannot be negative"
            );
        }
        steps = steps == null ? List.of() : List.copyOf(steps);
        if (!status.terminal() && failure != null) {
            throw new IllegalArgumentException(
                "Active snapshots cannot contain a terminal failure"
            );
        }
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(updatedAt, "updatedAt is required");
        Objects.requireNonNull(deadline, "deadline is required");
    }
}
