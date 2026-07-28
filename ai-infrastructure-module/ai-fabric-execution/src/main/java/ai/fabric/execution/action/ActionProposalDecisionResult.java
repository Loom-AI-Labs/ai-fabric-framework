package ai.fabric.execution.action;

import java.util.Objects;

public record ActionProposalDecisionResult(
    String receiptId,
    ActionProposalReceiptStatus status,
    ActionOutcomeView outcome,
    ActionProposalFailure failure
) {
    public ActionProposalDecisionResult {
        receiptId = Objects.requireNonNull(
            receiptId,
            "receiptId is required"
        ).trim();
        if (receiptId.isEmpty()) {
            throw new IllegalArgumentException("receiptId is required");
        }
        if (status == ActionProposalReceiptStatus.SUCCEEDED && outcome == null) {
            throw new IllegalArgumentException(
                "SUCCEEDED decision requires a safe outcome"
            );
        }
        if (status == null && failure == null) {
            throw new IllegalArgumentException(
                "Unavailable receipt decision requires a failure"
            );
        }
    }

    public boolean succeeded() {
        return status == ActionProposalReceiptStatus.SUCCEEDED;
    }
}
