package ai.fabric.execution.gateway;

import java.time.Instant;

public record GoalExecutionClaim(String invocationId, String authorityReference, String leaseOwner,
                                 Instant deadline, int attemptCount, boolean resumeRequested) {}
