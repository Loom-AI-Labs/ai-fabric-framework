package com.ai.infrastructure.core;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.provider.AIProviderManager;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AICoreServicePurposeRoutingTest {

    @Test
    void shouldRouteOrchestrationAndGenerationToDifferentProviders() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");

        providerConfig.getOpenai().setModel("gpt-4o-mini");
        providerConfig.getCohere().setModel("command-r-plus");

        AIProviderConfig.OrchestrationLlmConfig orchestration = new AIProviderConfig.OrchestrationLlmConfig();
        orchestration.setLlmProvider("cohere");
        orchestration.setModel("command-r-plus");
        providerConfig.setOrchestration(orchestration);

        AIProviderConfig.GenerationLlmConfig generation = new AIProviderConfig.GenerationLlmConfig();
        generation.setLlmProvider("openai");
        generation.setModel("gpt-4o");
        providerConfig.setGeneration(generation);

        AIProviderManager providerManager = mock(AIProviderManager.class);
        when(providerManager.generateContent(any(AIGenerationRequest.class), eq("cohere")))
            .thenReturn(AIGenerationResponse.builder().content("ok").model("command-r-plus").build());
        when(providerManager.generateContent(any(AIGenerationRequest.class), eq("openai")))
            .thenReturn(AIGenerationResponse.builder().content("ok").model("gpt-4o").build());

        @SuppressWarnings("unchecked")
        ObjectProvider<AIEmbeddingService> embeddingProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AISearchService> searchProvider = mock(ObjectProvider.class);

        AICoreService coreService = new AICoreService(
            providerConfig,
            providerManager,
            embeddingProvider,
            searchProvider,
            mock(PromptTemplateResolver.class),
            mock(PromptRenderer.class)
        );

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("id")
            .entityType("test")
            .generationType("gen")
            .prompt("hi")
            .build();

        coreService.generateContent(request, LlmPurpose.ORCHESTRATION);
        verify(providerManager).generateContent(any(AIGenerationRequest.class), eq("cohere"));

        coreService.generateContent(request, LlmPurpose.GENERATION);
        verify(providerManager).generateContent(any(AIGenerationRequest.class), eq("openai"));
    }

    @Test
    void shouldFallbackToGlobalProviderWhenPurposeNotConfigured() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.getOpenai().setModel("gpt-4o-mini");

        AIProviderManager providerManager = mock(AIProviderManager.class);
        when(providerManager.generateContent(any(AIGenerationRequest.class), eq("openai")))
            .thenReturn(AIGenerationResponse.builder().content("ok").model("gpt-4o-mini").build());

        @SuppressWarnings("unchecked")
        ObjectProvider<AIEmbeddingService> embeddingProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AISearchService> searchProvider = mock(ObjectProvider.class);

        AICoreService coreService = new AICoreService(
            providerConfig,
            providerManager,
            embeddingProvider,
            searchProvider,
            mock(PromptTemplateResolver.class),
            mock(PromptRenderer.class)
        );

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("id")
            .entityType("test")
            .generationType("gen")
            .prompt("hi")
            .build();

        coreService.generateContent(request, LlmPurpose.ORCHESTRATION);
        coreService.generateContent(request, LlmPurpose.GENERATION);
        verify(providerManager, org.mockito.Mockito.times(2)).generateContent(any(AIGenerationRequest.class), eq("openai"));
    }

    @Test
    void shouldApplyPurposeDefaultsWhenRequestOmitsModel() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.getOpenai().setModel("gpt-4o-mini");

        AIProviderManager providerManager = mock(AIProviderManager.class);
        when(providerManager.generateContent(any(AIGenerationRequest.class), eq("openai")))
            .thenAnswer(invocation -> {
                AIGenerationRequest sent = invocation.getArgument(0);
                assertThat(sent.getModel()).isEqualTo("gpt-4o-mini");
                return AIGenerationResponse.builder().content("ok").model(sent.getModel()).build();
            });

        @SuppressWarnings("unchecked")
        ObjectProvider<AIEmbeddingService> embeddingProvider = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AISearchService> searchProvider = mock(ObjectProvider.class);

        AICoreService coreService = new AICoreService(
            providerConfig,
            providerManager,
            embeddingProvider,
            searchProvider,
            mock(PromptTemplateResolver.class),
            mock(PromptRenderer.class)
        );

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("id")
            .entityType("test")
            .generationType("gen")
            .prompt("hi")
            .model(null)
            .build();

        coreService.generateContent(request, LlmPurpose.DEFAULT);
    }
}
