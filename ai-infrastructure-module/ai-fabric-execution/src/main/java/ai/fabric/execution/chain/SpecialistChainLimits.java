package ai.fabric.execution.chain;

import java.time.Duration;
import java.util.Objects;

/** Per-chain limits that may only narrow deployment ceilings. */
public record SpecialistChainLimits(
    Duration maxDuration,
    int maxManagerDecisions,
    int maxWorkerInvocations,
    int maxParallelWorkers,
    int maxInvocationsPerTarget,
    int maxProjectedResultCharacters
) {
    public SpecialistChainLimits {
        Objects.requireNonNull(maxDuration, "maxDuration is required");
        if (maxDuration.isZero() || maxDuration.isNegative()) {
            throw new IllegalArgumentException(
                "maxDuration must be positive"
            );
        }
        requirePositive(maxManagerDecisions, "maxManagerDecisions");
        requirePositive(maxWorkerInvocations, "maxWorkerInvocations");
        requirePositive(maxParallelWorkers, "maxParallelWorkers");
        requirePositive(maxInvocationsPerTarget, "maxInvocationsPerTarget");
        requirePositive(
            maxProjectedResultCharacters,
            "maxProjectedResultCharacters"
        );
        if (maxManagerDecisions < 2) {
            throw new IllegalArgumentException(
                "maxManagerDecisions must reserve a completion decision"
            );
        }
        if (maxParallelWorkers > maxWorkerInvocations) {
            throw new IllegalArgumentException(
                "maxParallelWorkers cannot exceed maxWorkerInvocations"
            );
        }
    }

    public static SpecialistChainLimits conservative() {
        return new SpecialistChainLimits(
            Duration.ofMinutes(2),
            4,
            4,
            3,
            1,
            12_000
        );
    }

    private static void requirePositive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
