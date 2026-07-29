package ai.fabric.execution.review.policy;

import java.util.Objects;
import java.util.regex.Pattern;

public record RegisteredReviewPolicy(
    ReviewPolicyDefinition definition,
    String contentHash
) {
    private static final Pattern SHA_256 = Pattern.compile("[a-f0-9]{64}");

    public RegisteredReviewPolicy {
        Objects.requireNonNull(definition, "definition is required");
        String normalized = Objects.requireNonNull(
            contentHash,
            "contentHash is required"
        ).trim();
        if (!SHA_256.matcher(normalized).matches()) {
            throw new IllegalArgumentException(
                "contentHash must be a lowercase SHA-256 value"
            );
        }
        contentHash = normalized;
    }

    public ReviewPolicyId id() {
        return definition.id();
    }
}
