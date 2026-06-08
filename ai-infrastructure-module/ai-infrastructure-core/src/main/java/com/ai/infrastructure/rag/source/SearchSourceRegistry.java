package com.ai.infrastructure.rag.source;

import com.ai.infrastructure.dto.RAGRequest;

import java.util.List;
import java.util.Map;

public interface SearchSourceRegistry {

    String contractVersion();

    List<String> supportedAdapterTypes();

    List<SearchSource> resolveSearchSources(RAGRequest request);

    default void recordSearchExecution(List<Map<String, Object>> sourceDiagnostics, boolean degraded) {
        // Default no-op: registries that do not maintain runtime health state can ignore executions.
    }

    default Map<String, Object> adminDiagnostics() {
        return Map.of();
    }
}
