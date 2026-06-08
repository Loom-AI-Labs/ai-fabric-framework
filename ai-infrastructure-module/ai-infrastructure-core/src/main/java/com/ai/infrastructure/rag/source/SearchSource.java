package com.ai.infrastructure.rag.source;

import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.dto.RAGRequest;

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

    boolean isEligible(RAGRequest request);

    AISearchResponse search(List<Double> queryVector, RAGRequest ragRequest, AISearchRequest baseSearchRequest);
}
