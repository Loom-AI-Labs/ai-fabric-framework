package ai.fabric.execution.review;

import ai.fabric.execution.context.ExecutionPrincipal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-owned reviewer identity. Applications must build this value from
 * authenticated runtime state, never from a public request body.
 */
public record TrustedReviewerContext(
    ExecutionPrincipal reviewer,
    String tenantId,
    Set<String> grantedScopes,
    String correlationId,
    Instant authenticatedAt
) {

    public TrustedReviewerContext {
        Objects.requireNonNull(reviewer, "reviewer is required");
        tenantId = normalizeOptional(tenantId);
        grantedScopes = immutableScopes(grantedScopes);
        correlationId = normalizeOptional(correlationId);
        if (correlationId == null) {
            correlationId = "review-" + UUID.randomUUID();
        }
        Objects.requireNonNull(
            authenticatedAt,
            "authenticatedAt is required"
        );
    }

    private static Set<String> immutableScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            String candidate = normalizeOptional(scope);
            if (candidate != null) {
                normalized.add(candidate);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
