package ai.fabric.rag.service;

import ai.fabric.core.AICoreService;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.core.AISearchService;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.RAGProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdvancedRAGServiceTest {

    @Mock
    private AISearchService aiSearchService;

    @Mock
    private AIEmbeddingService aiEmbeddingService;

    @Mock
    private AICoreService aiCoreService;

    @Mock
    private RAGProvider ragProvider;

    @Test
    void performAdvancedRAGReturnsFailedResponseForNullRequest() {
        AdvancedRAGService service = new AdvancedRAGService(
            aiSearchService,
            aiEmbeddingService,
            aiCoreService,
            ragProvider,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        AdvancedRAGResponse response = service.performAdvancedRAG(null);

        assertThat(response.getSuccess()).isFalse();
        assertThat(response.getErrorMessage()).contains("must not be null");
    }

    @Test
    void performAdvancedRAGUsesDefaultsForNullOptionalFields() {
        when(aiCoreService.generateText(any())).thenReturn("expanded one\nexpanded two");
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .success(true)
                .documents(List.of(
                    RAGResponse.RAGDocument.builder()
                        .id("doc-1")
                        .content("retrieved content")
                        .score(0.8)
                        .similarity(0.7)
                        .build()
                ))
                .build()
        );

        AdvancedRAGService service = new AdvancedRAGService(
            aiSearchService,
            aiEmbeddingService,
            aiCoreService,
            ragProvider,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        AdvancedRAGResponse response = service.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("test query")
                .expansionLevel(null)
                .rerankingStrategy(null)
                .contextOptimizationLevel(null)
                .maxDocuments(null)
                .maxResults(null)
                .build()
        );

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getExpansionLevel()).isEqualTo(2);
        assertThat(response.getRerankingStrategy()).isEqualTo("hybrid");
        assertThat(response.getContextOptimizationLevel()).isEqualTo("medium");
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getMetadata())
            .containsEntry("expansionLevel", 2)
            .containsEntry("rerankingStrategy", "hybrid")
            .containsEntry("contextOptimizationLevel", "medium")
            .containsEntry("maxDocuments", 5);
    }

    @Test
    void performAdvancedRAGDoesNotFailWhenDocumentSimilarityIsNull() {
        when(aiCoreService.generateText(any())).thenReturn("ok");

        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .success(true)
                .documents(List.of(
                    RAGResponse.RAGDocument.builder()
                        .id("doc-1")
                        .content("test content")
                        .type("FAQ")
                        .score(0.9)
                        .similarity(null)
                        .build()
                ))
                .build()
        );

        AdvancedRAGService service = new AdvancedRAGService(
            aiSearchService,
            aiEmbeddingService,
            aiCoreService,
            ragProvider,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        AdvancedRAGResponse response = service.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("test query")
                .expansionLevel(1)
                .rerankingStrategy("hybrid")
                .contextOptimizationLevel("low")
                .maxDocuments(5)
                .maxResults(5)
                .enableHybridSearch(true)
                .enableContextualSearch(false)
                .build()
        );

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getConfidenceScore()).isNotNull();
    }

    @Test
    void performAdvancedRAGUsesAuthoritativeContextInResponseGeneration() {
        when(aiCoreService.generateText(any())).thenReturn("ok");

        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .success(true)
                .documents(List.of(
                    RAGResponse.RAGDocument.builder()
                        .id("doc-1")
                        .content("retrieved content")
                        .score(0.9)
                        .similarity(0.9)
                        .build()
                ))
                .build()
        );

        AdvancedRAGService service = new AdvancedRAGService(
            aiSearchService,
            aiEmbeddingService,
            aiCoreService,
            ragProvider,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        service.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("summarize")
                .context("AUTHORITATIVE: pinned targets")
                .expansionLevel(1)
                .rerankingStrategy("hybrid")
                .contextOptimizationLevel("low")
                .maxDocuments(1)
                .maxResults(1)
                .enableHybridSearch(false)
                .enableContextualSearch(false)
                .build()
        );

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService, times(2)).generateText(promptCaptor.capture());

        String responseGenerationPrompt = promptCaptor.getAllValues().get(1);
        assertThat(responseGenerationPrompt).contains("PRIMARY FACTS");
        assertThat(responseGenerationPrompt).contains("AUTHORITATIVE: pinned targets");
        assertThat(responseGenerationPrompt).contains("retrieved content");
    }

    @Test
    void getStatisticsTracksAdvancedRAGRequests() {
        when(aiCoreService.generateText(any())).thenReturn("ok");
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .success(true)
                .documents(List.of(
                    RAGResponse.RAGDocument.builder()
                        .id("doc-1")
                        .content("retrieved content")
                        .score(0.8)
                        .similarity(0.7)
                        .build()
                ))
                .build()
        );

        AdvancedRAGService service = new AdvancedRAGService(
            aiSearchService,
            aiEmbeddingService,
            aiCoreService,
            ragProvider,
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Map<String, Object> initialStats = service.getStatistics();
        assertThat(initialStats)
            .containsEntry("totalRequests", 0L)
            .containsEntry("successfulRequests", 0L)
            .containsEntry("failedRequests", 0L);

        AdvancedRAGResponse failedResponse = service.performAdvancedRAG(null);
        AdvancedRAGResponse successfulResponse = service.performAdvancedRAG(
            AdvancedRAGRequest.builder()
                .query("test query")
                .expansionLevel(1)
                .rerankingStrategy("hybrid")
                .contextOptimizationLevel("low")
                .maxDocuments(1)
                .maxResults(1)
                .build()
        );

        Map<String, Object> stats = service.getStatistics();

        assertThat(failedResponse.getSuccess()).isFalse();
        assertThat(successfulResponse.getSuccess()).isTrue();
        assertThat(stats)
            .containsEntry("totalRequests", 2L)
            .containsEntry("successfulRequests", 1L)
            .containsEntry("failedRequests", 1L)
            .containsEntry("successRate", 0.5);
        assertThat((Double) stats.get("averageProcessingTimeMs")).isGreaterThanOrEqualTo(0.0);
        assertThat((Long) stats.get("lastProcessingTimeMs")).isGreaterThanOrEqualTo(0L);
        assertThat((Long) stats.get("lastRequestTimestamp")).isGreaterThan(0L);
        assertThat(stats.get("lastErrorMessage")).isNull();
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
