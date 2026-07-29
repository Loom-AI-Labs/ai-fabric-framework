package ai.fabric.execution.review.dispatch;

public record ReviewDispatchResult(
    boolean accepted,
    String externalReference,
    String failureReason
) {

    public ReviewDispatchResult {
        externalReference = normalize(externalReference, 240);
        failureReason = normalize(failureReason, 160);
        if (accepted && failureReason != null) {
            throw new IllegalArgumentException(
                "Accepted dispatch must not contain a failure reason"
            );
        }
        if (!accepted && failureReason == null) {
            throw new IllegalArgumentException(
                "Rejected dispatch requires a failure reason"
            );
        }
    }

    public static ReviewDispatchResult accepted(String reference) {
        return new ReviewDispatchResult(true, reference, null);
    }

    public static ReviewDispatchResult failed(String reason) {
        return new ReviewDispatchResult(false, null, reason);
    }

    private static String normalize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                "dispatch text exceeds the maximum length"
            );
        }
        return normalized;
    }
}
