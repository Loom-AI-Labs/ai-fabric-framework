package com.ai.fabric.runtime.search;

import com.ai.infrastructure.core.AISearchService;
import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.dto.RAGRequest;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.rag.source.ResolvedKnowledgeSource;
import com.ai.infrastructure.rag.source.SearchSource;

import java.util.List;

final class SharedIndexSearchSource implements SearchSource {

    private final ResolvedKnowledgeSource source;
    private final AISearchService searchService;
    private final VectorDatabaseService vectorDatabaseService;

    SharedIndexSearchSource(ResolvedKnowledgeSource source,
                            AISearchService searchService,
                            VectorDatabaseService vectorDatabaseService) {
        this.source = source;
        this.searchService = searchService;
        this.vectorDatabaseService = vectorDatabaseService;
    }

    @Override
    public ResolvedKnowledgeSource source() {
        return source;
    }

    @Override
    public boolean isEligible(RAGRequest request) {
        if (!source.isEnabled()) {
            return false;
        }
        if (source.getAuthModes().isEmpty()) {
            return true;
        }
        String authMode = request != null && request.getAuthContext() != null ? request.getAuthContext().getAuthMode() : null;
        return authMode != null && source.getAuthModes().stream().anyMatch(candidate -> candidate.equalsIgnoreCase(authMode));
    }

    @Override
    public AISearchResponse search(List<Double> queryVector, RAGRequest ragRequest, AISearchRequest baseSearchRequest) {
        AISearchRequest scopedRequest = SearchSourceResultSupport.scopedRequest(baseSearchRequest, source, 4);
        if (scopedRequest.getEntityType() == null || scopedRequest.getEntityType().isBlank()) {
            return AISearchResponse.builder()
                .results(List.of())
                .totalResults(0)
                .processingTimeMs(0L)
                .query(baseSearchRequest.getQuery())
                .build();
        }
        AISearchResponse response = executeSearch(queryVector, ragRequest, scopedRequest);
        return SearchSourceResultSupport.filterAndDecorate(response, source, source.getFilters());
    }

    private AISearchResponse executeSearch(List<Double> queryVector, RAGRequest ragRequest, AISearchRequest scopedRequest) {
        if (Boolean.TRUE.equals(ragRequest.getEnableHybridSearch())) {
            return vectorDatabaseService.hybridSearch(queryVector, ragRequest.getQuery(), scopedRequest);
        }
        if (Boolean.TRUE.equals(ragRequest.getEnableContextualSearch())) {
            AISearchRequest contextualRequest = AISearchRequest.builder()
                .query(scopedRequest.getQuery())
                .entityType(scopedRequest.getEntityType())
                .limit(scopedRequest.getLimit())
                .threshold(scopedRequest.getThreshold())
                .filters(scopedRequest.getFilters())
                .sortBy(scopedRequest.getSortBy())
                .context(ragRequest.getContext() != null ? ragRequest.getContext().toString() : null)
                .metadata(scopedRequest.getMetadata())
                .build();
            return vectorDatabaseService.search(queryVector, contextualRequest);
        }
        return searchService.search(queryVector, scopedRequest);
    }
}
