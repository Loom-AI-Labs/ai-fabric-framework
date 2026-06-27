package ai.fabric.rag.source;

import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;

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

    boolean isEligible(RAGRequest request);

    AISearchResponse search(List<Double> queryVector, RAGRequest ragRequest, AISearchRequest baseSearchRequest);
}
