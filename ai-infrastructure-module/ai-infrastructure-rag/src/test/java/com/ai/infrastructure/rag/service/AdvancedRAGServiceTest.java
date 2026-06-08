package com.ai.infrastructure.rag.service;

import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.dto.AdvancedRAGRequest;
import com.ai.infrastructure.dto.AdvancedRAGResponse;
import com.ai.infrastructure.dto.RAGRequest;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.core.AISearchService;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.spi.RAGProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

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

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
