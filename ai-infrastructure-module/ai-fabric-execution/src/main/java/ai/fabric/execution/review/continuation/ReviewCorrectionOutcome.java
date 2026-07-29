package ai.fabric.execution.review.continuation;

import ai.fabric.execution.action.ActionOutcomeView;

public record ReviewCorrectionOutcome(
    String successorReceiptId,
    ActionOutcomeView safeResult
) {

    public ReviewCorrectionOutcome {
        successorReceiptId = normalize(successorReceiptId);
        if ((successorReceiptId == null) == (safeResult == null)) {
            throw new IllegalArgumentException(
                "Correction must return either a successor receipt or a safe result"
            );
        }
    }

    public static ReviewCorrectionOutcome successor(String receiptId) {
        return new ReviewCorrectionOutcome(receiptId, null);
    }

    public static ReviewCorrectionOutcome completed(
        ActionOutcomeView safeResult
    ) {
        return new ReviewCorrectionOutcome(null, safeResult);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
