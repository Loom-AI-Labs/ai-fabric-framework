package ai.fabric.execution.review.auth;

public record ReviewerAuthorization(boolean allowed, String reason) {

    public ReviewerAuthorization {
        reason = normalize(reason);
        if (!allowed && reason == null) {
            throw new IllegalArgumentException(
                "Denied authorization requires a reason"
            );
        }
    }

    public static ReviewerAuthorization allow() {
        return new ReviewerAuthorization(true, null);
    }

    public static ReviewerAuthorization deny(String reason) {
        return new ReviewerAuthorization(false, reason);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() > 160) {
            throw new IllegalArgumentException(
                "authorization reason must not exceed 160 characters"
            );
        }
        return normalized;
    }
}
