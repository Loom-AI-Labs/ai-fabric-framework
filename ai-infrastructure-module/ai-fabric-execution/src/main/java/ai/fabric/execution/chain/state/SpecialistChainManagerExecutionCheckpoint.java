package ai.fabric.execution.chain.state;

import ai.fabric.execution.chain.SpecialistChainDirective;
import java.time.Instant;
import java.util.Objects;

/** Minimum validated manager lineage required to resume a transition. */
public record SpecialistChainManagerExecutionCheckpoint(
    String invocationId,
    SpecialistChainDirective directive,
    Instant startedAt,
    Instant completedAt
) {
    public SpecialistChainManagerExecutionCheckpoint {
        invocationId = Objects.requireNonNull(
            invocationId,
            "invocationId is required"
        ).trim();
        if (invocationId.isEmpty()) {
            throw new IllegalArgumentException("invocationId is required");
        }
        Objects.requireNonNull(directive, "directive is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        Objects.requireNonNull(completedAt, "completedAt is required");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException(
                "completedAt cannot precede startedAt"
            );
        }
    }
}
