package ai.fabric.execution.action;

import ai.fabric.intent.action.ActionResult;
import java.util.Objects;

/**
 * Trusted application command for reconciling an unknown write outcome.
 */
public record ActionProposalReconciliation(
    String receiptId,
    ActionProposalReceiptStatus finalStatus,
    ActionResult authoritativeResult
) {
    public ActionProposalReconciliation {
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
        if (finalStatus != ActionProposalReceiptStatus.SUCCEEDED
            && finalStatus != ActionProposalReceiptStatus.FAILED) {
            throw new IllegalArgumentException(
                "Reconciliation status must be SUCCEEDED or FAILED"
            );
        }
        Objects.requireNonNull(
            authoritativeResult,
            "authoritativeResult is required"
        );
    }
}
