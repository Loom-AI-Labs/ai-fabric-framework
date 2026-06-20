package ai.fabric.rag.service;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.config.RAGProperties;
import ai.fabric.vector.VectorDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RAGServicePropertiesTest {

    @Mock
    private AIEmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private VectorDatabase vectorDatabase;
    @Mock
    private AISearchService searchService;

    private RAGProperties properties;
    private RAGService ragService;

    @BeforeEach
    void setUp() {
        properties = new RAGProperties();
        properties.setDefaultLimit(3);
        properties.setDefaultThreshold(0.25d);
        properties.setEnableHybridSearch(false);
        properties.setEnableContextualSearch(false);
        properties.getIndexing().setMaxContentLength(12);

        ragService = new RAGService(
            new AIProviderConfig(),
            embeddingService,
            vectorDatabaseService,
            vectorDatabase,
            searchService,
            null,
            properties
        );

        when(embeddingService.executeEmbedding(any())).thenReturn(new AIEmbeddingService.EmbeddingExecution(
            AIEmbeddingResponse.builder()
                .embedding(List.of(0.1, 0.2, 0.3))
                .build(),
            false,
            "test",
            "test-embedding",
            1L,
            1L
        ));
        when(searchService.search(any(), any())).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .totalResults(0)
            .build());
    }

    @Test
    void performRagUsesConfiguredLimitAndThresholdWhenRequestOmitsThem() {
        ArgumentCaptor<AISearchRequest> searchRequest = ArgumentCaptor.forClass(AISearchRequest.class);

        RAGResponse response = ragService.performRag(RAGRequest.builder()
            .query("wireless headset")
            .entityType("faq")
            .build());

        assertThat(response.getSuccess()).isTrue();
        verify(searchService).search(any(), searchRequest.capture());
        assertThat(searchRequest.getValue())
            .extracting(AISearchRequest::getLimit, AISearchRequest::getThreshold)
            .containsExactly(3, 0.25d);
    }

    @Test
    void performRagKeepsRequestLimitAndThresholdOverrides() {
        ArgumentCaptor<AISearchRequest> searchRequest = ArgumentCaptor.forClass(AISearchRequest.class);

        ragService.performRag(RAGRequest.builder()
            .query("wireless headset")
            .entityType("faq")
            .limit(8)
            .threshold(0.44d)
            .build());

        verify(searchService).search(any(), searchRequest.capture());
        assertThat(searchRequest.getValue())
            .extracting(AISearchRequest::getLimit, AISearchRequest::getThreshold)
            .containsExactly(8, 0.44d);
    }

    @Test
    void performRagQueryUsesConfiguredSearchModeDefaults() {
        RAGResponse response = ragService.performRAGQuery(RAGRequest.builder()
            .query("wireless headset")
            .entityType("faq")
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getHybridSearchUsed()).isFalse();
        assertThat(response.getContextualSearchUsed()).isFalse();
        verify(searchService).search(any(), any());
        verify(vectorDatabaseService, never()).hybridSearch(any(), anyString(), any());
        verify(vectorDatabaseService, never()).search(any(), any());
    }

    @Test
    void performRagQueryReportsHybridFallbackWhenProviderDoesNotSupportNativeHybrid() {
        when(vectorDatabaseService.hybridSearch(any(), anyString(), any())).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .totalResults(0)
            .build());

        RAGResponse response = ragService.performRAGQuery(RAGRequest.builder()
            .query("wireless headset")
            .entityType("faq")
            .enableHybridSearch(true)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getHybridSearchUsed()).isFalse();
        assertThat(response.getContextualSearchUsed()).isFalse();
        assertThat(response.getMetadata())
            .containsEntry("hybridSearchRequested", true)
            .containsEntry("hybridSearchUsed", false)
            .containsEntry("hybridSearchMode", "fallback_vector")
            .containsEntry("vectorProviderSupportsHybridSearch", false)
            .containsEntry("searchExecutionPath", "vector_database_hybrid");
        verify(vectorDatabaseService).hybridSearch(any(), anyString(), any());
    }

    @Test
    void performRagQueryReportsNativeHybridWhenProviderSupportsHybridSearch() {
        when(vectorDatabaseService.supportsHybridSearch()).thenReturn(true);
        when(vectorDatabaseService.hybridSearch(any(), anyString(), any())).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .totalResults(0)
            .build());

        RAGResponse response = ragService.performRAGQuery(RAGRequest.builder()
            .query("wireless headset")
            .entityType("faq")
            .enableHybridSearch(true)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getHybridSearchUsed()).isTrue();
        assertThat(response.getContextualSearchUsed()).isFalse();
        assertThat(response.getMetadata())
            .containsEntry("hybridSearchRequested", true)
            .containsEntry("hybridSearchUsed", true)
            .containsEntry("hybridSearchMode", "native")
            .containsEntry("vectorProviderSupportsHybridSearch", true)
            .containsEntry("searchExecutionPath", "vector_database_hybrid");
        verify(vectorDatabaseService).hybridSearch(any(), anyString(), any());
    }

    @Test
    void indexContentUsesConfiguredContentLengthBeforeEmbeddingAndStorage() {
        when(embeddingService.generateEmbedding(any())).thenReturn(AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2, 0.3))
            .build());
        when(vectorDatabaseService.getVectorByEntity("faq", "faq-1")).thenReturn(Optional.empty());

        ragService.indexContent("faq", "faq-1", "abcdefghijklmnop", Map.of("category", "audio"));

        ArgumentCaptor<AIEmbeddingRequest> embeddingRequest = ArgumentCaptor.forClass(AIEmbeddingRequest.class);
        verify(embeddingService).generateEmbedding(embeddingRequest.capture());
        assertThat(embeddingRequest.getValue().getText()).isEqualTo("abcdefghijkl");
        verify(vectorDatabaseService).storeVector(
            "faq",
            "faq-1",
            "abcdefghijkl",
            List.of(0.1, 0.2, 0.3),
            Map.of("category", "audio")
        );
    }
}
