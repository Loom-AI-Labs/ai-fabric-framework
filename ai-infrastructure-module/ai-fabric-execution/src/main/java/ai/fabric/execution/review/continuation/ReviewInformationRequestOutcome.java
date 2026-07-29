package ai.fabric.execution.review.continuation;

public record ReviewInformationRequestOutcome(String publicMessage) {

    public ReviewInformationRequestOutcome {
        String normalized = java.util.Objects.requireNonNull(
            publicMessage,
            "publicMessage is required"
        ).trim();
        if (normalized.isEmpty() || normalized.length() > 1000) {
            throw new IllegalArgumentException(
                "publicMessage must contain 1 to 1000 characters"
            );
        }
        publicMessage = normalized;
    }
}
