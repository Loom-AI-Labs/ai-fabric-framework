package ai.fabric.intent.retrieval.connector;

import ai.fabric.dto.RAGResponse;

/**
 * Application extension point for narrowing an already policy-approved
 * external retrieval document.
 *
 * <p>Implementations may redact content or remove optional attribution and
 * metadata. The mandatory framework policy runs again afterward and rejects
 * identity, score, vector-space, attribution, or metadata widening.</p>
 */
@FunctionalInterface
public interface RetrievalDocumentSanitizer {

    RAGResponse.RAGDocument sanitize(
        RAGResponse.RAGDocument document,
        RetrievalDocumentSanitizationContext context
    );
}
