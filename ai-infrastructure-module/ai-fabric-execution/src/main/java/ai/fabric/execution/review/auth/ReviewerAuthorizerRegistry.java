package ai.fabric.execution.review.auth;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ReviewerAuthorizerRegistry {

    private final Map<String, ReviewerAuthorizer> authorizers;

    public ReviewerAuthorizerRegistry(List<ReviewerAuthorizer> values) {
        Map<String, ReviewerAuthorizer> loaded = new LinkedHashMap<>();
        for (ReviewerAuthorizer value :
            values == null ? List.<ReviewerAuthorizer>of() : values) {
            Objects.requireNonNull(value, "authorizer must not be null");
            String id = requireId(value.id());
            if (loaded.putIfAbsent(id, value) != null) {
                throw new IllegalStateException(
                    "Duplicate reviewer authorizer: " + id
                );
            }
        }
        this.authorizers = Map.copyOf(loaded);
    }

    public ReviewerAuthorizer require(String id) {
        String normalized = requireId(id);
        ReviewerAuthorizer authorizer = authorizers.get(normalized);
        if (authorizer == null) {
            throw new IllegalArgumentException(
                "Reviewer authorizer is not registered: " + normalized
            );
        }
        return authorizer;
    }

    public List<ReviewerAuthorizer> list() {
        return List.copyOf(authorizers.values());
    }

    private static String requireId(String value) {
        String normalized = Objects.requireNonNull(
            value,
            "authorizer ID is required"
        ).trim();
        if (!normalized.matches(
                "[a-z][a-z0-9-]{0,79}@[A-Za-z0-9][A-Za-z0-9._-]{0,39}"
            )) {
            throw new IllegalArgumentException(
                "Authorizer ID must use lowercase-name@version"
            );
        }
        return normalized;
    }
}
