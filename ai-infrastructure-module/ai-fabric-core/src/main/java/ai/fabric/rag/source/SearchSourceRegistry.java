package ai.fabric.rag.source;

import ai.fabric.dto.RAGRequest;

import java.util.List;
import java.util.Map;

public interface SearchSourceRegistry {

    String contractVersion();

    List<String> supportedAdapterTypes();

    List<SearchSource> resolveSearchSources(RAGRequest request);

    default void recordSearchExecution(List<Map<String, Object>> sourceDiagnostics, boolean degraded) {
        // Default behavior leaves runtime health state unchanged.
    }

    default Map<String, Object> adminDiagnostics() {
        return Map.of();
    }
}
