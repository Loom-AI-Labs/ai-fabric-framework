package ai.fabric.execution.chain;

import java.util.Objects;

/** Safe external failure without provider payloads or hidden reasoning. */
public record SpecialistChainFailure(
    String reason,
    String publicMessage,
    boolean retryable
) {
    public SpecialistChainFailure {
        reason = requireText(reason, "reason", 160);
        publicMessage = requireText(
            publicMessage,
            "publicMessage",
            1_000
        );
    }

    private static String requireText(
        String value,
        String field,
        int maximum
    ) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > maximum) {
            throw new IllegalArgumentException(
                field + " must not exceed " + maximum + " characters"
            );
        }
        return normalized;
    }
}
