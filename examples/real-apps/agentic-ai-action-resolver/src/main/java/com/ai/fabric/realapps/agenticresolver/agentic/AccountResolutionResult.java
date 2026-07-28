package com.ai.fabric.realapps.agenticresolver.agentic;

import java.util.List;

/**
 * Typed domain assessment returned by the account resolver specialist.
 */
public record AccountResolutionResult(
    Assessment assessment,
    String summary,
    List<Blocker> blockers
) {
    public AccountResolutionResult {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
    }

    public enum Assessment {
        READY,
        BLOCKED,
        INSUFFICIENT_EVIDENCE
    }

    public record Blocker(
        Requirement requirement,
        String explanation,
        String recommendedNextStep
    ) {}

    public enum Requirement {
        ACTIVE_SUBSCRIPTION,
        VERIFIED_PAYMENT_METHOD,
        VALIDATED_BILLING_ADDRESS,
        OTHER
    }
}
