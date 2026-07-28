package ai.fabric.execution.action;

import java.util.Objects;

/**
 * Safe public decision contract. It intentionally contains no identity or parameters.
 */
public record ActionProposalDecisionRequest(
    String receiptId,
    ActionProposalDecision decision
) {
    public ActionProposalDecisionRequest {
        receiptId = Objects.requireNonNull(
            receiptId,
            "receiptId is required"
        ).trim();
        if (receiptId.isEmpty()) {
            throw new IllegalArgumentException("receiptId is required");
        }
        if (receiptId.length() > 120) {
            throw new IllegalArgumentException(
                "receiptId must not exceed 120 characters"
            );
        }
        Objects.requireNonNull(decision, "decision is required");
    }
}
