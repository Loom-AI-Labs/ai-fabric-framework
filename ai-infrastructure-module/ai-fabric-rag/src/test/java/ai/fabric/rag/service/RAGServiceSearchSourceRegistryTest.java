package ai.fabric.rag.service;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.source.SearchSource;
import ai.fabric.rag.source.SearchSourceRegistry;
import ai.fabric.vector.VectorDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGServiceSearchSourceRegistryTest {

    @Mock
    private AIEmbeddingService embeddingService;
    @Mock
    private VectorDatabaseService vectorDatabaseService;
    @Mock
    private VectorDatabase vectorDatabase;
    @Mock
    private AISearchService searchService;
    @Mock
    private SearchSourceRegistry searchSourceRegistry;
    @Mock
    private SearchSource privateSource;
    @Mock
    private SearchSource sharedSource;

    private RAGService ragService;

    @BeforeEach
    void setUp() {
        AIProviderConfig config = new AIProviderConfig();
        AIEmbeddingResponse embeddingResponse = AIEmbeddingResponse.builder()
            .embedding(List.of(0.1, 0.2, 0.3))
            .build();
        when(embeddingService.executeEmbedding(any())).thenReturn(
            new AIEmbeddingService.EmbeddingExecution(
                embeddingResponse,
                false,
                "openai",
                "text-embedding-3-small",
                9L,
                9L
            )
        );

        ragService = new RAGService(
            config,
            embeddingService,
            vectorDatabaseService,
            vectorDatabase,
            searchService,
            searchSourceRegistry
        );

    }

    @Test
    void performRagMergesResolvedSearchSourcesAndAddsAttribution() {
        configureSourceIdentities();
        when(privateSource.isEligible(any())).thenReturn(true);
        when(sharedSource.isEligible(any())).thenReturn(true);
        when(searchSourceRegistry.resolveSearchSources(any())).thenReturn(List.of(privateSource, sharedSource));
        when(privateSource.search(any(), any(), any())).thenReturn(
            AISearchResponse.builder()
                .results(List.of(Map.of(
                    "id", "product-1",
                    "content", "Alienware m18 R2 is available.",
                    "score", 0.74,
                    "similarity", 0.74,
                    "metadata", Map.of(
                        "knowledgeSourceId", "deployment-private-vector",
                        "knowledgeSourceType", "deployment-private-vector",
                        "knowledgeSourceAdapterType", "deployment-private-vector",
                        "knowledgeSourceAttributionLabel", "Deployment knowledge"
                    )
                )))
                .totalResults(1)
                .maxScore(0.74)
                .build()
        );
        when(sharedSource.search(any(), any(), any())).thenReturn(
            AISearchResponse.builder()
                .results(List.of(Map.of(
                    "id", "product-2",
                    "content", "Shared catalog lists Alienware m18 R2 deals.",
                    "score", 0.92,
                    "similarity", 0.92,
                    "metadata", Map.of(
                        "knowledgeSourceId", "shared-catalog",
                        "knowledgeSourceType", "shared-vector",
                        "knowledgeSourceAdapterType", "shared-index",
                        "knowledgeSourceAttributionLabel", "Shared catalog"
                    )
                )))
                .totalResults(1)
                .maxScore(0.92)
                .build()
        );

        RAGResponse response = ragService.performRag(RAGRequest.builder()
            .query("tell me about Alienware m18 R2")
            .entityType("product")
            .limit(5)
            .threshold(0.1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).hasSize(2);
        assertThat(response.getDocuments().get(0).getSource()).isEqualTo("Shared catalog");
        assertThat(response.getDocuments().get(0).getMetadata())
            .containsEntry("knowledgeSourceId", "shared-catalog")
            .containsEntry("knowledgeSourceAdapterType", "shared-index");
        assertThat(response.getMetadata())
            .containsEntry("searchSourceCount", 2)
            .containsEntry("searchSourceEligibleCount", 2)
            .containsEntry("searchSourceAttemptedCount", 2)
            .containsEntry("searchSourceSucceededCount", 2)
            .containsEntry("searchSourceFailedCount", 0)
            .containsEntry("searchSourceSkippedCount", 0)
            .containsEntry("searchSourcesDegraded", false)
            .containsEntry("searchSourceIds", List.of("shared-catalog", "deployment-private-vector"))
            .containsEntry("searchSourceAdapterTypes", List.of("shared-index", "deployment-private-vector"));
        assertThat(response.getMetadata().get("searchSourceDiagnostics")).isInstanceOf(List.class);
        verify(searchSourceRegistry).recordSearchExecution(any(), eq(false));
    }

    @Test
    void performRagContinuesWhenOneSearchSourceFailsAndMarksDegraded() {
        configureSourceIdentities();
        when(privateSource.isEligible(any())).thenReturn(true);
        when(sharedSource.isEligible(any())).thenReturn(true);
        when(searchSourceRegistry.resolveSearchSources(any())).thenReturn(List.of(privateSource, sharedSource));
        when(privateSource.search(any(), any(), any())).thenThrow(new IllegalStateException("private source unavailable"));
        when(sharedSource.search(any(), any(), any())).thenReturn(
            AISearchResponse.builder()
                .results(List.of(Map.of(
                    "id", "product-2",
                    "content", "Shared catalog lists Alienware m18 R2 deals.",
                    "score", 0.92,
                    "similarity", 0.92,
                    "metadata", Map.of(
                        "knowledgeSourceId", "shared-catalog",
                        "knowledgeSourceType", "shared-vector",
                        "knowledgeSourceAdapterType", "shared-index",
                        "knowledgeSourceAttributionLabel", "Shared catalog"
                    )
                )))
                .totalResults(1)
                .maxScore(0.92)
                .build()
        );

        RAGResponse response = ragService.performRag(RAGRequest.builder()
            .query("tell me about Alienware m18 R2")
            .entityType("product")
            .limit(5)
            .threshold(0.1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getSource()).isEqualTo("Shared catalog");
        assertThat(response.getMetadata())
            .containsEntry("searchSourceCount", 2)
            .containsEntry("searchSourceEligibleCount", 2)
            .containsEntry("searchSourceAttemptedCount", 2)
            .containsEntry("searchSourceSucceededCount", 1)
            .containsEntry("searchSourceFailedCount", 1)
            .containsEntry("searchSourceSkippedCount", 0)
            .containsEntry("searchSourcesDegraded", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) response.getMetadata().get("searchSourceDiagnostics");
        assertThat(diagnostics)
            .anySatisfy(entry -> assertThat(entry)
                .containsEntry("sourceId", "deployment-private-vector")
                .containsEntry("status", "FAILED")
                .containsEntry("errorType", "IllegalStateException"))
            .anySatisfy(entry -> assertThat(entry)
                .containsEntry("sourceId", "shared-catalog")
                .containsEntry("status", "SUCCEEDED"));
        verify(searchSourceRegistry).recordSearchExecution(any(), eq(true));
    }

    @Test
    void performRagTracksSkippedIneligibleSourcesWithoutMarkingDegraded() {
        configureSourceIdentities();
        when(privateSource.isEligible(any())).thenReturn(true);
        when(sharedSource.isEligible(any())).thenReturn(false);
        when(searchSourceRegistry.resolveSearchSources(any())).thenReturn(List.of(privateSource, sharedSource));
        when(privateSource.search(any(), any(), any())).thenReturn(
            AISearchResponse.builder()
                .results(List.of(Map.of(
                    "id", "product-1",
                    "content", "Alienware m18 R2 is available.",
                    "score", 0.74,
                    "similarity", 0.74,
                    "metadata", Map.of(
                        "knowledgeSourceId", "deployment-private-vector",
                        "knowledgeSourceType", "deployment-private-vector",
                        "knowledgeSourceAdapterType", "deployment-private-vector",
                        "knowledgeSourceAttributionLabel", "Deployment knowledge"
                    )
                )))
                .totalResults(1)
                .maxScore(0.74)
                .build()
        );

        RAGResponse response = ragService.performRag(RAGRequest.builder()
            .query("tell me about Alienware m18 R2")
            .entityType("product")
            .limit(5)
            .threshold(0.1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getMetadata())
            .containsEntry("searchSourceCount", 2)
            .containsEntry("searchSourceEligibleCount", 1)
            .containsEntry("searchSourceAttemptedCount", 1)
            .containsEntry("searchSourceSucceededCount", 1)
            .containsEntry("searchSourceFailedCount", 0)
            .containsEntry("searchSourceSkippedCount", 1)
            .containsEntry("searchSourcesDegraded", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> diagnostics = (List<Map<String, Object>>) response.getMetadata().get("searchSourceDiagnostics");
        assertThat(diagnostics)
            .anySatisfy(entry -> assertThat(entry)
                .containsEntry("sourceId", "shared-catalog")
                .containsEntry("status", "SKIPPED")
                .containsEntry("reason", "ineligible"));
        verify(searchSourceRegistry).recordSearchExecution(any(), eq(false));
    }

    @Test
    void performRagTreatsNullResolvedSearchSourcesAsEmptyResultSet() {
        when(searchSourceRegistry.resolveSearchSources(any())).thenReturn(null);

        RAGResponse response = ragService.performRag(RAGRequest.builder()
            .query("tell me about Alienware m18 R2")
            .entityType("product")
            .limit(5)
            .threshold(0.1)
            .build());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).isEmpty();
        assertThat(response.getMetadata())
            .containsEntry("searchSourceCount", 0)
            .containsEntry("searchSourceEligibleCount", 0)
            .containsEntry("searchSourceAttemptedCount", 0)
            .containsEntry("searchSourceSucceededCount", 0)
            .containsEntry("searchSourceFailedCount", 0)
            .containsEntry("searchSourceSkippedCount", 0)
            .containsEntry("searchSourcesDegraded", false);
    }

    private void configureSourceIdentities() {
        when(privateSource.sourceId()).thenReturn("deployment-private-vector");
        when(privateSource.sourceType()).thenReturn("deployment-private-vector");
        when(privateSource.adapterType()).thenReturn("deployment-private-vector");
        when(sharedSource.sourceId()).thenReturn("shared-catalog");
        when(sharedSource.sourceType()).thenReturn("shared-vector");
        when(sharedSource.adapterType()).thenReturn("shared-index");
    }
}
