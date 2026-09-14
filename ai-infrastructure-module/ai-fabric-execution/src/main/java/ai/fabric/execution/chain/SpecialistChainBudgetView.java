package ai.fabric.execution.chain;

/** Remaining bounded resources visible to the manager. */
public record SpecialistChainBudgetView(
    int managerDecisions,
    int workerInvocations,
    int parallelWorkers,
    int projectedResultCharacters,
    long durationMillis
) {
    public SpecialistChainBudgetView {
        if (managerDecisions < 0
            || workerInvocations < 0
            || parallelWorkers < 0
            || projectedResultCharacters < 0
            || durationMillis < 0) {
            throw new IllegalArgumentException(
                "Remaining chain budgets must not be negative"
            );
        }
    }
}
