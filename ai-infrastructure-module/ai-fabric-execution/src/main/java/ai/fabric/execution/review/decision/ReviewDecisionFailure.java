package ai.fabric.execution.review.decision;

public record ReviewDecisionFailure(
    String reason,
    String publicMessage,
    boolean retryable
) {

    public ReviewDecisionFailure {
        reason = requireText(reason, "reason", 160);
        publicMessage = requireText(
            publicMessage,
            "publicMessage",
            1000
        );
    }

    private static String requireText(
        String value,
        String field,
        int maxLength
    ) {
        String normalized = java.util.Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maxLength + " characters"
            );
        }
        return normalized;
    }
}
