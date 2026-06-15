package ai.fabric.spi;

import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;

/**
 * Service Provider Interface for advanced RAG operations.
 *
 * <p>This SPI allows the orchestration pipeline (core module) to optionally invoke
 * advanced retrieval pipelines (query expansion, re-ranking, context optimization)
 * without creating a compile-time dependency on a concrete implementation module.</p>
 *
 * <p><strong>Separation of concerns:</strong> The provider is responsible for retrieval and
 * producing high-quality {@link AdvancedRAGResponse#getContext() context}. The orchestrator is responsible
 * for deciding whether generation is needed and for generating the final user-facing answer. Providers MAY
 * populate {@link AdvancedRAGResponse#getResponse()} as an optimization, but callers MUST treat it as optional
 * and generate an answer from {@code context} when it is absent.</p>
 *
 * <p><strong>Module Boundary:</strong></p>
 * <ul>
 *   <li>The core module depends only on this interface and the DTOs.</li>
 *   <li>The RAG module provides an implementation (e.g., {@code AdvancedRAGService}).</li>
 *   <li>Applications may provide custom implementations.</li>
 * </ul>
 *
 * <p><strong>Thread Safety:</strong> Implementations MUST be thread-safe.</p>
 *
 * <p><strong>Error handling:</strong> Implementations SHOULD return a non-null response.
 * On failure, return {@code success=false} and set {@code errorMessage} rather than throwing.</p>
 *
 * @since 1.0
 */
public interface AdvancedRAGProvider {

    /**
     * Perform an advanced retrieval pipeline (query expansion, re-ranking, context optimization).
     *
     * <p>Implementations MAY use LLM calls for query expansion and context optimization. They MAY also
     * return a pre-generated {@link AdvancedRAGResponse#getResponse() response} as an optimization, but
     * callers must treat it as optional and generate from {@link AdvancedRAGResponse#getContext()} when needed.</p>
     *
     * @param request advanced RAG request configuration (must not be null)
     * @return advanced RAG response (never null; on error use {@code success=false})
     * @throws IllegalArgumentException if request is null or request.query is blank
     */
    AdvancedRAGResponse performAdvancedRAG(AdvancedRAGRequest request);
}

