package ai.fabric.intent.orchestration.workflow;

import java.time.Duration;
import java.util.List;

/** Finite lifetime and operational spin protection, never an authorization grant. */
public record WorkflowGoalPolicy(boolean enabled, Duration maxElapsed, Duration continuationDelay,
                                 int maxConsecutiveNoProgress, int maxConsecutivePlannerFailures,
                                 List<String> delegatedProfiles) {
    public WorkflowGoalPolicy {
        delegatedProfiles = delegatedProfiles == null ? List.of() : List.copyOf(delegatedProfiles);
        if (maxElapsed == null || maxElapsed.isNegative() || maxElapsed.isZero()
            || maxElapsed.compareTo(Duration.ofDays(30)) > 0 || continuationDelay == null
            || continuationDelay.isNegative() || continuationDelay.compareTo(Duration.ofHours(1)) > 0
            || maxConsecutiveNoProgress < 1 || maxConsecutiveNoProgress > 1000
            || maxConsecutivePlannerFailures < 1 || maxConsecutivePlannerFailures > 100
            || delegatedProfiles.size() > 64 || delegatedProfiles.stream().anyMatch(s -> s == null || s.isBlank())
            || delegatedProfiles.stream().distinct().count() != delegatedProfiles.size())
            throw new IllegalArgumentException("Invalid goal lifetime, scheduling or recovery ceilings");
    }
    public static WorkflowGoalPolicy disabled() {
        return new WorkflowGoalPolicy(false, Duration.ofDays(1), Duration.ofSeconds(1), 8, 4, List.of());
    }
}
