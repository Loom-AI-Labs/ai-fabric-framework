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

    @Test
    void configuredLearnedRerankingRunsOnSharedRetrievalBeforeFinalContextSelection() {
        var reranker = org.mockito.Mockito.mock(ai.fabric.spi.RerankingProvider.class);
        when(reranker.providerId()).thenReturn("cohere");
        when(reranker.modelId()).thenReturn("qualified-model");
        when(reranker.rerank(any())).thenReturn(new ai.fabric.spi.RerankingProvider.Result(List.of(
            new ai.fabric.spi.RerankingProvider.RankedCandidate("candidate-1", .9),
            new ai.fabric.spi.RerankingProvider.RankedCandidate("candidate-0", .2)),
            new ai.fabric.spi.RerankingProvider.Usage(Map.of("search_units", 1L)), "request-1"));
        properties.setRetrievalProfile(ai.fabric.rag.RetrievalProfile.dense());
        properties.getReranking().setEnabled(true);
        properties.getReranking().setProvider("cohere");
        properties.getReranking().setModel("qualified-model");
        when(vectorDatabaseService.retrievalCapabilities("product"))
            .thenReturn(ai.fabric.rag.RetrievalCapabilities.denseOnly("dense fixture"));
        when(searchService.search(any(), any())).thenReturn(AISearchResponse.builder().results(List.of(
            Map.of("id", "a", "content", "approved one", "score", .9),
            Map.of("id", "b", "content", "approved two", "score", .8))).build());
        var service = new RAGService(new AIProviderConfig(), embeddingService, vectorDatabaseService, vectorDatabase,
            searchService, null, properties, reranker);
        var response = service.performRag(RAGRequest.builder().query("question").entityType("product").build());
        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).extracting(RAGResponse.RAGDocument::getId).containsExactly("b", "a");
        assertThat(response.getContext()).contains("approved one", "approved two");
        var diagnostic = (Map<?, ?>) response.getMetadata().get("retrievalDiagnostics");
        assertThat(((Map<?, ?>) diagnostic.get("reranking")).get("used")).isEqualTo(true);
        verify(reranker).rerank(any());
    }

    @Mock
    private AIEmbeddingService embeddingService;

    @Test
    void keywordOnlyUsesNoEmbeddingOrDenseProviderInEitherRetrievalEntryPoint() {
        var profile = new ai.fabric.rag.RetrievalProfile(ai.fabric.rag.RetrievalProfile.Mode.KEYWORD,
            20, 20, 40, 6, 60, ai.fabric.rag.RetrievalProfile.FailurePolicy.FAIL);
        when(vectorDatabaseService.retrievalCapabilities("product"))
            .thenReturn(new ai.fabric.rag.RetrievalCapabilities(true, true, "BM25", "existing", ""));
        when(vectorDatabaseService.keywordSearch(anyString(), any())).thenReturn(AISearchResponse.builder().results(List.of(
            Map.of("id", "exact", "content", "approved keyword evidence", "score", 2.5))).build());
        for (boolean queryOnly : List.of(false, true)) {
            var request = RAGRequest.builder().query("exact identifier").entityType("product").retrievalProfile(profile).build();
            var response = queryOnly ? ragService.performRAGQuery(request) : ragService.performRag(request);
            assertThat(response.getSuccess()).isTrue();
            assertThat(response.getDocuments()).extracting(RAGResponse.RAGDocument::getId).containsExactly("exact");
            assertThat(response.getModel()).isNull();
            assertThat(response.getMetadata()).containsEntry("embeddingUsed", false);
        }
        org.mockito.Mockito.verifyNoInteractions(embeddingService, searchService);
        verify(vectorDatabaseService, org.mockito.Mockito.times(2)).keywordSearch(anyString(), any());
    }

    @Test
    void requestCanDisableRankingForABaselineWithoutMutatingTheHostProfile() {
        var reranker = org.mockito.Mockito.mock(ai.fabric.spi.RerankingProvider.class);
        when(reranker.providerId()).thenReturn("cohere");
        when(reranker.modelId()).thenReturn("qualified-model");
        when(reranker.rerank(any())).thenReturn(new ai.fabric.spi.RerankingProvider.Result(List.of(
            new ai.fabric.spi.RerankingProvider.RankedCandidate("candidate-1", .9),
            new ai.fabric.spi.RerankingProvider.RankedCandidate("candidate-0", .2)),
            new ai.fabric.spi.RerankingProvider.Usage(Map.of("input_tokens", 80L)), "native-receipt"));
        properties.setRetrievalProfile(ai.fabric.rag.RetrievalProfile.dense());
        properties.getReranking().setEnabled(true);
        properties.getReranking().setProvider("cohere");
        properties.getReranking().setModel("qualified-model");
        when(vectorDatabaseService.retrievalCapabilities("product"))
            .thenReturn(ai.fabric.rag.RetrievalCapabilities.denseOnly("dense fixture"));
        when(searchService.search(any(), any())).thenReturn(AISearchResponse.builder().results(List.of(
            Map.of("id", "a", "content", "approved one", "score", .9),
            Map.of("id", "b", "content", "approved two", "score", .8))).build());
        var service = new RAGService(new AIProviderConfig(), embeddingService, vectorDatabaseService, vectorDatabase,
            searchService, null, properties, reranker);
        var request = RAGRequest.builder().query("question").entityType("product").rerankingEnabled(false).build();
        var baseline = service.performRAGQuery(request);
        assertThat(baseline.getSuccess()).isTrue();
        assertThat(baseline.getDocuments()).extracting(RAGResponse.RAGDocument::getId).containsExactly("a", "b");
        assertThat(((Map<?, ?>) baseline.getMetadata().get("retrievalDiagnostics")).get("reranking"))
            .isEqualTo(Map.of("used", false, "status", "REQUEST_DISABLED"));
        verify(reranker, never()).rerank(any());
        var ranked = service.performRAGQuery(request.toBuilder().rerankingEnabled(null).build());
        assertThat(ranked.getDocuments()).extracting(RAGResponse.RAGDocument::getId).containsExactly("b", "a");
        verify(reranker).rerank(any());
        assertThat(properties.getReranking().isEnabled()).isTrue();
    }
    @Mock
    private VectorDatabaseService vectorDatabaseService;

    @Test
    void requestCannotEnableHostDisabledReranking() {
        var reranker = org.mockito.Mockito.mock(ai.fabric.spi.RerankingProvider.class);
        properties.setRetrievalProfile(ai.fabric.rag.RetrievalProfile.dense());
        properties.getReranking().setEnabled(false);
        when(vectorDatabaseService.retrievalCapabilities("product"))
            .thenReturn(ai.fabric.rag.RetrievalCapabilities.denseOnly("dense fixture"));
        when(searchService.search(any(), any())).thenReturn(AISearchResponse.builder().results(List.of(
            Map.of("id", "a", "content", "approved evidence", "score", .9))).build());
        var service = new RAGService(new AIProviderConfig(), embeddingService, vectorDatabaseService, vectorDatabase,
            searchService, null, properties, reranker);

        var response = service.performRAGQuery(RAGRequest.builder().query("question").entityType("product")
            .rerankingEnabled(true).build());

        assertThat(response.getSuccess()).isTrue();
        org.mockito.Mockito.verifyNoInteractions(reranker);
        assertThat(properties.getReranking().isEnabled()).isFalse();
    }

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
