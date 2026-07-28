package ai.fabric.execution.action;

import java.time.Instant;
import java.util.Objects;

/**
 * Public, non-executable view of a durable action proposal.
 */
public record ActionProposalView(
    String receiptId,
    String actionName,
    String confirmationMessage,
    ActionProposalReceiptStatus status,
    Instant createdAt,
    Instant expiresAt
) {
    public ActionProposalView {
        receiptId = requireText(receiptId, "receiptId");
        actionName = requireText(actionName, "actionName");
        confirmationMessage = requireText(
            confirmationMessage,
            "confirmationMessage"
        );
        Objects.requireNonNull(status, "status is required");
        Objects.requireNonNull(createdAt, "createdAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
