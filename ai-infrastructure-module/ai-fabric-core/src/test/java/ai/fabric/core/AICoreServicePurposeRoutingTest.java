package ai.fabric.core;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.dto.AIEmbeddingRequest;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.provider.AIProviderManager;
import ai.fabric.provider.ProviderRequestOverrideSupport;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AICoreServicePurposeRoutingTest {

    @Test
    void providerFailureDoesNotExposePromptOrProviderDetails() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.getOpenai().setModel("gpt-4o-mini");

        AIProviderManager providerManager = mock(AIProviderManager.class);
        when(providerManager.generateContent(
            any(AIGenerationRequest.class),
            eq("openai")
        )).thenThrow(
            new IllegalStateException(
                "invalid_api_key secret-provider-detail"
            )
        );

        @SuppressWarnings("unchecked")
        ObjectProvider<AIEmbeddingService> embeddingProvider =
            mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<AISearchService> searchProvider =
            mock(ObjectProvider.class);
        AICoreService coreService = new AICoreService(
            providerConfig,
            providerManager,
            embeddingProvider,
            searchProvider,
            mock(PromptTemplateResolver.class),
            mock(PromptRenderer.class)
        );

        String sensitivePrompt =
            "Update my address to secret-address-marker";
        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("id")
            .entityType("test")
            .generationType("gen")
            .prompt(sensitivePrompt)
            .build();

        Logger logger =
            (Logger) LoggerFactory.getLogger(AICoreService.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
            new ListAppender<>();
        appender.start();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            assertThatThrownBy(
                () -> coreService.generateContent(
                    request,
                    LlmPurpose.ORCHESTRATION
                )
            )
                .isInstanceOf(AIServiceException.class)
                .hasMessage("Failed to generate AI content")
                .hasMessageNotContaining("secret-provider-detail")
                .hasMessageNotContaining(sensitivePrompt);

            List<String> messages = appender.list.stream()
                .map(event -> event.getFormattedMessage())
                .toList();
            assertThat(messages)
                .anyMatch(message ->
                    message.contains("cause=IllegalStateException")
                )
                .noneMatch(message ->
                    message.contains("secret-provider-detail")
                        || message.contains(sensitivePrompt)
                );
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }

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

    @Test
    void shouldApplyPurposeConnectionOverrideWhenRequestAlreadyHasGenerationOptions() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.getOpenai().setModel("gpt-4o-mini");

        AIProviderConfig.OrchestrationLlmConfig orchestration = new AIProviderConfig.OrchestrationLlmConfig();
        orchestration.setLlmProvider("openai");
        orchestration.setApiKey("purpose-key");
        orchestration.setBaseUrl("https://purpose.example.com/v1");
        orchestration.setDeploymentName("purpose-deployment");
        orchestration.setApiVersion("2026-01-01");
        providerConfig.setOrchestration(orchestration);

        AIProviderManager providerManager = mock(AIProviderManager.class);
        when(providerManager.generateContent(any(AIGenerationRequest.class), eq("openai")))
            .thenAnswer(invocation -> {
                AIGenerationRequest sent = invocation.getArgument(0);
                assertThat(sent.getModel()).isEqualTo("request-model");
                assertThat(sent.getMaxTokens()).isEqualTo(123);
                assertThat(sent.getTemperature()).isEqualTo(0.42d);

                assertThat(sent.getParameters())
                    .containsEntry("caller", "kept")
                    .containsKey(ProviderRequestOverrideSupport.PARAM_PROVIDER_CONNECTION_OVERRIDE);
                @SuppressWarnings("unchecked")
                Map<String, Object> override = (Map<String, Object>) sent.getParameters()
                    .get(ProviderRequestOverrideSupport.PARAM_PROVIDER_CONNECTION_OVERRIDE);
                assertThat(override)
                    .containsEntry(ProviderRequestOverrideSupport.KEY_API_KEY, "purpose-key")
                    .containsEntry(ProviderRequestOverrideSupport.KEY_BASE_URL, "https://purpose.example.com/v1")
                    .containsEntry(ProviderRequestOverrideSupport.KEY_DEPLOYMENT_NAME, "purpose-deployment")
                    .containsEntry(ProviderRequestOverrideSupport.KEY_API_VERSION, "2026-01-01");
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
            .parameters(Map.of("caller", "kept"))
            .model("request-model")
            .maxTokens(123)
            .temperature(0.42d)
            .build();

        coreService.generateContent(request, LlmPurpose.ORCHESTRATION);
    }

    @Test
    void shouldApplyEmbeddingConnectionOverrideWhenRequestAlreadyHasModel() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setEmbeddingProvider("openai");
        providerConfig.getOpenai().setEmbeddingModel("text-embedding-3-small");
        providerConfig.setEmbeddingApiKey("embedding-key");
        providerConfig.setEmbeddingBaseUrl("https://embeddings.example.com/v1");
        providerConfig.setEmbeddingDeploymentName("embedding-deployment");
        providerConfig.setEmbeddingApiVersion("2026-01-01");

        AIProviderManager providerManager = mock(AIProviderManager.class);

        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        when(embeddingService.generateEmbedding(any(AIEmbeddingRequest.class)))
            .thenReturn(AIEmbeddingResponse.builder()
                .embedding(java.util.List.of(0.1d, 0.2d))
                .dimensions(2)
                .model("request-model")
                .build());

        @SuppressWarnings("unchecked")
        ObjectProvider<AIEmbeddingService> embeddingProvider = mock(ObjectProvider.class);
        when(embeddingProvider.getIfAvailable()).thenReturn(embeddingService);
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

        coreService.generateEmbedding(AIEmbeddingRequest.builder()
            .text("embed me")
            .model("request-model")
            .parameters(Map.of("caller", "kept"))
            .build());

        var captor = forClass(AIEmbeddingRequest.class);
        verify(embeddingService).generateEmbedding(captor.capture());
        AIEmbeddingRequest sent = captor.getValue();
        assertThat(sent.getModel()).isEqualTo("request-model");
        assertThat(sent.getParameters())
            .containsEntry("caller", "kept")
            .containsKey(ProviderRequestOverrideSupport.PARAM_PROVIDER_CONNECTION_OVERRIDE);
        @SuppressWarnings("unchecked")
        Map<String, Object> override = (Map<String, Object>) sent.getParameters()
            .get(ProviderRequestOverrideSupport.PARAM_PROVIDER_CONNECTION_OVERRIDE);
        assertThat(override)
            .containsEntry(ProviderRequestOverrideSupport.KEY_API_KEY, "embedding-key")
            .containsEntry(ProviderRequestOverrideSupport.KEY_BASE_URL, "https://embeddings.example.com/v1")
            .containsEntry(ProviderRequestOverrideSupport.KEY_DEPLOYMENT_NAME, "embedding-deployment")
            .containsEntry(ProviderRequestOverrideSupport.KEY_API_VERSION, "2026-01-01");
    }
}
