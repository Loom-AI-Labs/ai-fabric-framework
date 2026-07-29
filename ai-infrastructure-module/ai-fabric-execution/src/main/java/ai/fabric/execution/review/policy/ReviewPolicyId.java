package ai.fabric.execution.review.policy;

import java.util.Objects;

/**
 * Stable exact-version identity for an application-approved review policy.
 */
public record ReviewPolicyId(String name, String version) {

    public ReviewPolicyId {
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    public static ReviewPolicyId of(String name, String version) {
        return new ReviewPolicyId(name, version);
    }

    @Override
    public String toString() {
        return name + "@" + version;
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        if (normalized.length() > 120) {
            throw new IllegalArgumentException(
                field + " must not exceed 120 characters"
            );
        }
        return normalized;
    }
}
