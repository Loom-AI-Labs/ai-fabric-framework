package ai.fabric.intent.orchestration.workflow;

import java.time.Duration;

/** Server-owned opt-in. These are execution ceilings, not permissions. */
public record WorkflowPolicy(boolean enabled, int maxActions, int maxWrites,
                             int maxModelCalls, int maxCorrections, Duration timeout, WorkflowGoalPolicy goal) {
    public WorkflowPolicy(boolean enabled, int maxActions, int maxWrites, int maxModelCalls,
                          int maxCorrections, Duration timeout) {
        this(enabled, maxActions, maxWrites, maxModelCalls, maxCorrections, timeout, WorkflowGoalPolicy.disabled());
    }
    public WorkflowPolicy {
        goal = goal == null ? WorkflowGoalPolicy.disabled() : goal;
        if (maxActions < 1 || maxActions > 64 || maxWrites < 0 || maxWrites > maxActions
            || maxModelCalls < 1 || maxModelCalls > 64 || maxCorrections < 0 || maxCorrections > 8
            || timeout == null || timeout.isZero() || timeout.isNegative()
            || timeout.compareTo(Duration.ofSeconds(140)) > 0) {
            throw new IllegalArgumentException("Invalid workflow execution ceilings");
        }
    }

    public boolean goalMode() { return enabled && goal.enabled(); }

    public static WorkflowPolicy disabled() {
        return new WorkflowPolicy(false, 8, 0, 16, 2, Duration.ofSeconds(120));
    }
}
