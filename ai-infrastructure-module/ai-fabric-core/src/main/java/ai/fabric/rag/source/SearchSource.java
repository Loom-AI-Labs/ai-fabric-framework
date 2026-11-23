package ai.fabric.rag.source;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.rag.RetrievalCapabilities;

import java.util.List;

public interface SearchSource {

    ResolvedKnowledgeSource source();

    default String sourceId() {
        return source().getId();
    }

    default String sourceType() {
        return source().getType();
    }

    default String adapterType() {
        return source().getAdapterType();
    }

    default String attributionLabel() {
        return source().getAttributionLabel();
    }

    default boolean supportsHybridSearch() {
        return false;
    }

    default RetrievalCapabilities retrievalCapabilities(RAGRequest request) {
        return RetrievalCapabilities.denseOnly("Source does not report a ready lexical index");
    }

    /**
     * Must preserve the same scope, audience and active-version checks as search.
     * The default is an explicit unsupported capability boundary; callers inspect
     * retrievalCapabilities before attempting this operation, never assume lexical success.
     */
    default AISearchResponse keywordSearch(RAGRequest request, AISearchRequest searchRequest) {
        throw new UnsupportedOperationException("Source does not support lexical retrieval");
    }

    boolean isEligible(RAGRequest request);

    AISearchResponse search(List<Double> queryVector, RAGRequest ragRequest, AISearchRequest baseSearchRequest);
}
