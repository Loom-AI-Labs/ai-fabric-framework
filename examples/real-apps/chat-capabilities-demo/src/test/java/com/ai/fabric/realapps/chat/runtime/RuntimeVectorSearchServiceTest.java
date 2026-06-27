package com.ai.fabric.realapps.chat.runtime;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeVectorSearchServiceTest {

    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final RuntimeVectorSearchService service = new RuntimeVectorSearchService(aiCoreService);

    @Test
    void searchesRawRuntimeVectorSpaceAndSanitizesSensitiveFields() {
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(Map.of(
                "entityId", "SKU-0001",
                "score", 0.91d,
                "embedding", List.of(1.0d, 2.0d),
                "metadata", Map.of(
                    "category", "Headphones",
                    "apiToken", "hidden",
                    "nested", Map.of("promptText", "hidden", "source", "data-sync")
                )
            )))
            .build());

        RuntimeVectorSearchService.RuntimeVectorSearchResult result =
            service.search("product", "wireless headphones", 500, -1.0d);

        assertThat(result.vectorSpace()).isEqualTo("product");
        assertThat(result.limit()).isEqualTo(50);
        assertThat(result.threshold()).isZero();
        assertThat(result.returnedResults()).isEqualTo(1);
        assertThat(result.results()).hasSize(1);
        assertThat(result.results().getFirst())
            .containsEntry("entityId", "SKU-0001")
            .containsEntry("score", 0.91d)
            .doesNotContainKey("embedding");
        Map<String, Object> metadata = castMap(result.results().getFirst().get("metadata"));
        Map<String, Object> nested = castMap(metadata.get("nested"));
        assertThat(metadata)
            .containsEntry("category", "Headphones")
            .doesNotContainKey("apiToken");
        assertThat(nested)
            .containsEntry("source", "data-sync")
            .doesNotContainKey("promptText");

        ArgumentCaptor<AISearchRequest> requestCaptor = ArgumentCaptor.forClass(AISearchRequest.class);
        verify(aiCoreService).performSearch(requestCaptor.capture());
        AISearchRequest request = requestCaptor.getValue();
        assertThat(request.getEntityType()).isEqualTo("product");
        assertThat(request.getQuery()).isEqualTo("wireless headphones");
        assertThat(request.getLimit()).isEqualTo(50);
        assertThat(request.getThreshold()).isZero();
    }

    @Test
    void rejectsBlankVectorSpaceAndQuery() {
        assertThatThrownBy(() -> service.search(" ", "query", 5, 0.0d))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("vectorSpace");
        assertThatThrownBy(() -> service.search("product", " ", 5, 0.0d))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("query");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}
