package ai.fabric.intent.orchestration.workflow;

import ai.fabric.dto.ProviderOutcome;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable scheduling and compact recovery context; receipts remain authoritative in the journal. */
public record WorkflowGoalProgress(int segmentNumber, Instant segmentStartedAt, Instant segmentDeadline,
                                   WorkflowAllowance.Usage segmentStartUsage, Instant nextEligibleAt,
                                   int consecutiveNoProgress, int consecutivePlannerFailures,
                                   Delegation activeDelegation, List<Failure> recentFailures) {
    public record Delegation(String operationId, WorkflowDelegation task, String definitionFingerprint) {}
    public record Failure(String code, ProviderOutcome providerOutcome) {}
    public WorkflowGoalProgress {
        Objects.requireNonNull(segmentStartedAt); Objects.requireNonNull(segmentDeadline);
        Objects.requireNonNull(segmentStartUsage);
        recentFailures = recentFailures == null ? List.of() : List.copyOf(recentFailures);
        if (segmentNumber < 1 || consecutiveNoProgress < 0 || consecutivePlannerFailures < 0 || recentFailures.size() > 8)
            throw new IllegalArgumentException("Invalid goal progress");
    }
    public WorkflowGoalProgress nextSegment(Instant now, Instant deadline, WorkflowAllowance.Usage usage) {
        return new WorkflowGoalProgress(Math.incrementExact(segmentNumber), now, deadline, usage, null,
            consecutiveNoProgress, consecutivePlannerFailures, activeDelegation, recentFailures);
    }
    public WorkflowGoalProgress scheduled(Instant at) {
        return new WorkflowGoalProgress(segmentNumber, segmentStartedAt, segmentDeadline, segmentStartUsage, at,
            consecutiveNoProgress, consecutivePlannerFailures, activeDelegation, recentFailures);
    }
    public WorkflowGoalProgress delegation(Delegation value) {
        return new WorkflowGoalProgress(segmentNumber, segmentStartedAt, segmentDeadline, segmentStartUsage, nextEligibleAt,
            consecutiveNoProgress, consecutivePlannerFailures, value, recentFailures);
    }
    public WorkflowGoalProgress observed(boolean progress, boolean plannerFailure, Failure failure) {
        var history = new java.util.ArrayList<>(recentFailures);
        if (failure != null) history.add(failure);
        while (history.size() > 8) history.removeFirst();
        return new WorkflowGoalProgress(segmentNumber, segmentStartedAt, segmentDeadline, segmentStartUsage, nextEligibleAt,
            progress ? 0 : Math.incrementExact(consecutiveNoProgress),
            plannerFailure ? Math.incrementExact(consecutivePlannerFailures) : 0, activeDelegation, history);
    }
}
