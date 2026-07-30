package ai.fabric.intent.retrieval.connector;

import java.util.Objects;

/**
 * Safe request-scoped facts available to retrieval document sanitizers.
 */
public record RetrievalDocumentSanitizationContext(
    String requestedVectorSpace,
    int effectiveTopK,
    int documentIndex
) {
    public RetrievalDocumentSanitizationContext {
        requestedVectorSpace = Objects.requireNonNull(
            requestedVectorSpace,
            "requestedVectorSpace is required"
        ).trim();
        if (requestedVectorSpace.isEmpty()) {
            throw new IllegalArgumentException(
                "requestedVectorSpace is required"
            );
        }
        if (effectiveTopK < 1) {
            throw new IllegalArgumentException(
                "effectiveTopK must be positive"
            );
        }
        if (documentIndex < 0) {
            throw new IllegalArgumentException(
                "documentIndex must not be negative"
            );
        }
    }
}
