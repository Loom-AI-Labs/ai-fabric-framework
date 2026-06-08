package com.ai.infrastructure.intent.orchestration;

import com.ai.infrastructure.config.ResponseSanitizationProperties;
import com.ai.infrastructure.config.SmartSuggestionsProperties;
import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.LlmPurpose;
import com.ai.infrastructure.dto.AdvancedRAGRequest;
import com.ai.infrastructure.dto.AdvancedRAGResponse;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.NextStepRecommendation;
import com.ai.infrastructure.dto.RAGRequest;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.intent.IntentQueryExtractor;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.extraction.IntentExtractionInput;
import com.ai.infrastructure.intent.history.IntentHistoryService;
import com.ai.infrastructure.intent.action.AIActionHandler;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.orchestration.pipeline.DefaultOrchestrationPipeline;
import com.ai.infrastructure.intent.orchestration.pipeline.Pipeline;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.AccessControlStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.HistoryPersistenceStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.IntentExtractionStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.IntentHandlingStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.MetadataBuildingStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.OrchestrationResultNormalizationStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.ResponseSanitizationStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.SecurityAnalysisStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.SmartSuggestionsStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.VectorSpaceResolutionStep;
import com.ai.infrastructure.spi.AdvancedRAGProvider;
import com.ai.infrastructure.spi.RAGProvider;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import com.ai.infrastructure.security.ResponseSanitizer;
import com.ai.infrastructure.security.AISecurityService;
import com.ai.infrastructure.access.AIAccessControlService;
import com.ai.infrastructure.dto.AIAccessControlResponse;
import com.ai.infrastructure.dto.AISecurityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RAGOrchestratorTest {

    @Mock
    private IntentQueryExtractor intentQueryExtractor;

    @Mock
    private AIActionRegistry actionHandlerRegistry;

    @Mock
    private RAGProvider ragProvider;

    @Mock
    private AICoreService aiCoreService;

    @Mock
    private AIServiceConfig aiServiceConfig;

    @Mock
    private ObjectProvider<AdvancedRAGProvider> advancedRagProvider;

    @Mock
    private AIActionHandler actionHandler;

    @Mock
    private IntentHistoryService intentHistoryService;

    @Mock
    private AISecurityService securityService;

    @Mock
    private AIAccessControlService accessControlService;

    private ResponseSanitizer responseSanitizer;
    private SmartSuggestionsProperties smartSuggestionsProperties;

    private RAGOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        smartSuggestionsProperties = new SmartSuggestionsProperties();
        ResponseSanitizationProperties sanitizationProperties = new ResponseSanitizationProperties();
        sanitizationProperties.setEnabled(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<PIIDetectionService> piiProvider = mock(ObjectProvider.class);
        lenient().when(piiProvider.getIfAvailable()).thenReturn(null);
        responseSanitizer = new ResponseSanitizer(piiProvider, sanitizationProperties);
        when(intentHistoryService.recordIntent(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(securityService.analyzeRequest(any())).thenReturn(
            AISecurityResponse.builder()
                .shouldBlock(false)
                .accessAllowed(true)
                .success(true)
                .build()
        );
        when(accessControlService.checkAccess(any())).thenReturn(
            AIAccessControlResponse.builder()
                .accessGranted(true)
                .success(true)
                .build()
        );
        
        // Create pipeline with all steps
        Pipeline pipeline = createPipeline();
        orchestrator = new RAGOrchestrator(pipeline);
    }
    
    /**
     * Creates a pipeline with all required steps for testing.
     */
    private Pipeline createPipeline() {
        var normalizationProperties = new com.ai.infrastructure.config.OrchestrationResultNormalizationProperties();
        normalizationProperties.setEnabled(true);
        normalizationProperties.setDebugSnapshotEnabled(false);
        var normalizer = new OrchestrationResultNormalizer();

        ObjectProvider<RAGProvider> ragProviderProvider = mock(ObjectProvider.class);
        lenient().when(ragProviderProvider.getIfAvailable()).thenReturn(ragProvider);

        var vectorSpaceRoutingProperties = new com.ai.infrastructure.config.VectorSpaceRoutingProperties();
        var rankBasedMerger = new com.ai.infrastructure.intent.vectorspace.RankBasedMerger();
        var vectorSpaceRouter = mock(com.ai.infrastructure.intent.vectorspace.VectorSpaceRouter.class);
        lenient().when(vectorSpaceRouter.route(any(), anyString())).thenReturn(
            com.ai.infrastructure.intent.vectorspace.RoutingResult.builder()
                .success(true)
                .vectorSpace("policies")
                .strategy(com.ai.infrastructure.intent.vectorspace.RoutingStrategy.HEURISTIC)
                .confidence(0.5d)
                .rationale("test default")
                .build()
        );

        ObjectProvider<com.ai.infrastructure.intent.extraction.ProgressiveIntentExtractionEngine> progressiveEngineProvider =
            mock(ObjectProvider.class);
        lenient().when(progressiveEngineProvider.getIfAvailable()).thenReturn(null);

        var orchestrationProperties = new OrchestrationProperties();
        @SuppressWarnings("unchecked")
        ObjectProvider<KnowledgeBaseOverviewService> overviewProvider = mock(ObjectProvider.class);
        lenient().when(overviewProvider.getIfAvailable()).thenReturn(null);

        PromptTemplateResolver promptTemplateResolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
        PromptRenderer promptRenderer = new PromptRenderer();

	        List<PipelineStep> steps = List.of(
	            new SecurityAnalysisStep(securityService),
	            new AccessControlStep(accessControlService),
	            new IntentExtractionStep(intentQueryExtractor, progressiveEngineProvider),
	            new VectorSpaceResolutionStep(vectorSpaceRouter, orchestrationProperties, vectorSpaceRoutingProperties, overviewProvider),
	            new IntentHandlingStep(actionHandlerRegistry, ragProviderProvider, aiCoreService, aiServiceConfig, advancedRagProvider,
	                vectorSpaceRoutingProperties, rankBasedMerger,
	                new com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties(),
	                new com.ai.infrastructure.config.PostActionGenerationProperties(),
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class),
                orchestrationProperties,
                overviewProvider,
                null,
                new InMemoryPendingActionStore(),
                new InMemoryActionDraftStore(),
                promptTemplateResolver,
                promptRenderer),
            new OrchestrationResultNormalizationStep(normalizer, normalizationProperties),
            new MetadataBuildingStep(normalizationProperties),
            new SmartSuggestionsStep(smartSuggestionsProperties, ragProviderProvider),
            new ResponseSanitizationStep(responseSanitizer),
            new HistoryPersistenceStep(intentHistoryService)
        );
        return new DefaultOrchestrationPipeline(steps);
    }

    @Test
    void shouldExecuteActionIntent() {
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_subscription")
            .actionParams(Map.of("reason", "too expensive"))
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(actionHandlerRegistry.findHandler("cancel_subscription")).thenReturn(Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(true);
        when(actionHandler.requiresConfirmation()).thenReturn(true);
        when(actionHandler.getConfirmationMessage(any(), any())).thenReturn("Confirm cancellation?");
        when(actionHandlerRegistry.findMetadata("cancel_subscription")).thenReturn(Optional.empty());
        when(actionHandler.executeAction(any(), any()))
            .thenReturn(ActionResult.builder().success(true).message("Cancelled").build());

        OrchestrationResult result = orchestrator.orchestrate("Cancel my plan", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user-1"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Cancelled");
        assertThat(result.getData()).containsEntry("confirmationMessage", "Confirm cancellation?");
        assertThat(result.getSanitizedPayload()).isNotEmpty();
        verify(intentHistoryService).recordIntent(eq("user-1"), any(), eq("Cancel my plan"), any(), any());
    }

    @Test
    void shouldReturnErrorWhenHandlerMissing() {
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("unknown_action")
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(actionHandlerRegistry.findHandler("unknown_action")).thenReturn(Optional.empty());

        OrchestrationResult result = orchestrator.orchestrate("Do something", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldHandleActionValidationFailure() {
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_subscription")
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(actionHandlerRegistry.findHandler("cancel_subscription")).thenReturn(Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(false);

        OrchestrationResult result = orchestrator.orchestrate("Cancel", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_DENIED);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldInvokeHandleErrorWhenExecutionThrows() {
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_subscription")
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(actionHandlerRegistry.findHandler("cancel_subscription")).thenReturn(Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(true);
        when(actionHandler.requiresConfirmation()).thenReturn(true);
        when(actionHandler.getConfirmationMessage(any(), any())).thenReturn("Confirm?");
        when(actionHandlerRegistry.findMetadata("cancel_subscription")).thenReturn(Optional.empty());
        when(actionHandler.executeAction(any(), any())).thenThrow(new IllegalStateException("boom"));
        when(actionHandler.handleError(any(), any()))
            .thenReturn(ActionResult.builder().success(false).message("boom").build());

        OrchestrationResult result = orchestrator.orchestrate("Cancel", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(result.getMessage()).isEqualTo("boom");
    }

    @Test
    void shouldProcessInformationIntentViaRag() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace("policies")
            .requiresGeneration(true)
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        RAGResponse ragResponse = RAGResponse.builder()
            .context("Refunds take 5-7 days.")
            .documents(List.of())
            .build();
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(ragResponse);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class))).thenReturn(
            AIGenerationResponse.builder().content("Refunds take 5-7 days.").build()
        );

        OrchestrationResult result = orchestrator.orchestrate("What is your refund policy?", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Refunds take 5-7 days.");

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRAGQuery(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEntityType()).isEqualTo("policies");
    }

    @Test
    void shouldRouteInformationIntentToGenerationWhenRequested() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("product_recommendations")
            .vectorSpace("product")
            .optimizedQuery("Product entities where price_usd < 100 and stock_status = 'in_stock'")
            .requiresGeneration(true)
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        RAGResponse ragResponse = RAGResponse.builder()
            .context("Top picks context.")
            .documents(List.of())
            .success(true)
            .build();
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(ragResponse);
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class))).thenReturn(
            AIGenerationResponse.builder().content("Here are top picks.").build()
        );

        OrchestrationResult result = orchestrator.orchestrate("Recommend products under $100", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Here are top picks.");

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRAGQuery(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getMetadata()).containsEntry("optimizedQuery", intent.getOptimizedQuery());
        verify(ragProvider, never()).performRag(any(RAGRequest.class));
    }

    @Test
    void shouldUseAdvancedRagWhenLlmRequestsIt_andFallbackToOrchestratorGenerationWhenProviderOmitsResponse() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("recommend_products")
            .vectorSpace("product")
            .optimizedQuery("Recommend the best audio gear for commuting with noise cancellation and battery life considerations.")
            .requiresGeneration(true)
            .needsAdvancedRAG(true)
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        AIServiceConfig.FeatureFlags features = AIServiceConfig.FeatureFlags.builder()
            .enableAdvancedRAG(true)
            .autoEnableAdvancedRAGForComplexQueries(false)
            .build();
        when(aiServiceConfig.getFeatures()).thenReturn(features);

        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        when(advancedRagProvider.getIfAvailable()).thenReturn(provider);

        when(provider.performAdvancedRAG(any(AdvancedRAGRequest.class))).thenReturn(
            AdvancedRAGResponse.builder()
                .success(true)
                .context("Some relevant context from advanced retrieval.")
                .response(null) // Force orchestrator fallback generation
                .documents(List.of())
                .build()
        );
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class))).thenReturn(
            AIGenerationResponse.builder().content("Generated answer from orchestrator.").build()
        );

        OrchestrationResult result = orchestrator.orchestrate("What should I buy for commuting?", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Generated answer from orchestrator.");

        verify(provider).performAdvancedRAG(any(AdvancedRAGRequest.class));
        verify(ragProvider, never()).performRAGQuery(any(RAGRequest.class));
        verify(ragProvider, never()).performRag(any(RAGRequest.class));
        verify(aiCoreService).generateTextResponse(anyString(), any(LlmPurpose.class));
    }

    @Test
    void shouldNotUseAdvancedRagWhenLlmDeclinesEvenIfHeuristicsWouldMatch() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("recommend_products")
            .vectorSpace("product")
            .optimizedQuery("Can you recommend a wireless headset with ANC, low latency, multi-device pairing, and long battery life?")
            .requiresGeneration(true)
            .needsAdvancedRAG(false) // LLM explicitly says no
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        AIServiceConfig.FeatureFlags features = AIServiceConfig.FeatureFlags.builder()
            .enableAdvancedRAG(true)
            .autoEnableAdvancedRAGForComplexQueries(true) // Would enable heuristics if LLM didn't decide
            .build();
        when(aiServiceConfig.getFeatures()).thenReturn(features);

        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        when(advancedRagProvider.getIfAvailable()).thenReturn(provider);

        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .context("Basic retrieval context.")
                .documents(List.of())
                .success(true)
                .build()
        );
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class))).thenReturn(
            AIGenerationResponse.builder().content("Basic generated answer.").build()
        );

        OrchestrationResult result = orchestrator.orchestrate("Recommend audio gear", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Basic generated answer.");

        verify(provider, never()).performAdvancedRAG(any(AdvancedRAGRequest.class));
        verify(ragProvider).performRAGQuery(any(RAGRequest.class));
    }

    @Test
    void shouldNotUseAdvancedRagWhenConfigDisablesIt_evenIfLlmRequestsIt() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("recommend_products")
            .vectorSpace("product")
            .optimizedQuery("Recommend audio gear for commuting with ANC, comfort, and battery life.")
            .requiresGeneration(true)
            .needsAdvancedRAG(true)
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        AIServiceConfig.FeatureFlags features = AIServiceConfig.FeatureFlags.builder()
            .enableAdvancedRAG(false) // Config constraint: disable advanced
            .autoEnableAdvancedRAGForComplexQueries(true)
            .build();
        when(aiServiceConfig.getFeatures()).thenReturn(features);

        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        when(advancedRagProvider.getIfAvailable()).thenReturn(provider);

        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .context("Basic retrieval context.")
                .documents(List.of())
                .success(true)
                .build()
        );
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class))).thenReturn(
            AIGenerationResponse.builder().content("Basic generated answer.").build()
        );

        OrchestrationResult result = orchestrator.orchestrate("Recommend audio gear", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Basic generated answer.");

        verify(provider, never()).performAdvancedRAG(any(AdvancedRAGRequest.class));
        verify(ragProvider).performRAGQuery(any(RAGRequest.class));
    }

    @Test
    void shouldFallBackToHeuristicsWhenLlmDoesNotProvideAdvancedDecision() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("recommend_products")
            .vectorSpace("product")
            .optimizedQuery("Can you recommend a wireless headset with ANC, low latency, multi-device pairing, and long battery life?")
            .requiresGeneration(true)
            .build(); // needsAdvancedRAG is null (LLM did not provide)
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        AIServiceConfig.FeatureFlags features = AIServiceConfig.FeatureFlags.builder()
            .enableAdvancedRAG(true)
            .autoEnableAdvancedRAGForComplexQueries(true)
            .build();
        when(aiServiceConfig.getFeatures()).thenReturn(features);

        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        when(advancedRagProvider.getIfAvailable()).thenReturn(provider);
        when(provider.performAdvancedRAG(any(AdvancedRAGRequest.class))).thenReturn(
            AdvancedRAGResponse.builder()
                .success(true)
                .context("Advanced provider context.")
                .response("Advanced provider answer.")
                .documents(List.of())
                .build()
        );

        OrchestrationResult result = orchestrator.orchestrate("Recommend audio gear", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Advanced provider answer.");

        verify(provider).performAdvancedRAG(any(AdvancedRAGRequest.class));
        verify(aiCoreService, never()).generateTextResponse(anyString(), any(LlmPurpose.class));
        verify(ragProvider, never()).performRAGQuery(any(RAGRequest.class));
    }

    @Test
    void shouldHandleOutOfScopeIntent() {
        Intent intent = Intent.builder()
            .type(IntentType.OUT_OF_SCOPE)
            .actionParams(Map.of(
                "reason", "Unsupported domain",
                "userMessage", "I can help with supported product and policy questions."
            ))
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        OrchestrationResult result = orchestrator.orchestrate("Build me a spaceship", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.OUT_OF_SCOPE);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("I can help with supported product and policy questions.");
    }

    @Test
    void shouldUseSafeDefaultForOutOfScopeWhenModelOnlyProvidesDirectAnswer() {
        Intent intent = Intent.builder()
            .type(IntentType.OUT_OF_SCOPE)
            .directAnswer("I cannot provide legal advice.")
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        OrchestrationResult result = orchestrator.orchestrate("Give me legal advice", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.OUT_OF_SCOPE);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("approved product", "order questions");
        assertThat(result.getMessage()).doesNotContain("legal advice");
    }

    @Test
    void shouldDenyActionForAnonymousSession() {
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_subscription")
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());

        OrchestrationContext anonymous = OrchestrationContext.forSession("sess-123");

        OrchestrationResult result = orchestrator.orchestrate("Cancel my plan", anonymous);

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_DENIED);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    void shouldHandleCompoundIntents() {
        Intent actionIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_subscription")
            .build();
        Intent infoIntent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .build();
        MultiIntentResponse compound = MultiIntentResponse.builder()
            .intents(List.of(actionIntent, infoIntent))
            .build();

        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class))).thenReturn(compound);
        when(actionHandlerRegistry.findHandler("cancel_subscription")).thenReturn(Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(true);
        when(actionHandler.requiresConfirmation()).thenReturn(true);
        when(actionHandler.getConfirmationMessage(any(), any())).thenReturn("Confirm?");
        when(actionHandlerRegistry.findMetadata("cancel_subscription")).thenReturn(Optional.empty());
        when(actionHandler.executeAction(any(), any()))
            .thenReturn(ActionResult.builder().success(true).message("Cancelled").build());
        lenient().when(ragProvider.performRag(any())).thenReturn(RAGResponse.builder()
            .context("info")
            .success(true)
            .build());

        OrchestrationResult result = orchestrator.orchestrate("Cancel and explain refund", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        // When compound children include pending clarification/confirmation, keep the compound visible.
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.COMPOUND_HANDLED);
        assertThat(result.getChildren()).hasSize(2);
    }

    @Test
    void shouldIncludeNextStepRecommendations() {
        NextStepRecommendation recommendation = NextStepRecommendation.builder()
            .intent("view_billing_history")
            .confidence(0.71)
            .build();
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("update_payment_method")
            .nextStepRecommended(recommendation)
            .build();
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(actionHandlerRegistry.findHandler("update_payment_method")).thenReturn(Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(true);
        when(actionHandler.requiresConfirmation()).thenReturn(true);
        when(actionHandler.getConfirmationMessage(any(), any())).thenReturn("Confirm?");
        when(actionHandlerRegistry.findMetadata("update_payment_method")).thenReturn(Optional.empty());
        when(actionHandler.executeAction(any(), any()))
            .thenReturn(ActionResult.builder().success(true).message("Updated").build());
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(RAGResponse.builder()
            .context("Your payment method is confirmed.")
            .documents(List.of())
            .build());

        OrchestrationResult result = orchestrator.orchestrate("Update my payment method", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getNextSteps()).containsExactly(recommendation);
        assertThat(result.getSmartSuggestion())
            .containsEntry("intent", "view_billing_history")
            .containsEntry("response", "Your payment method is confirmed.");
        assertThat(result.getData()).containsKey("smartSuggestion");
    }

    @Test
    void shouldSkipSmartSuggestionWhenConfidenceBelowThreshold() {
        // Recreate pipeline with updated properties
        smartSuggestionsProperties.setMinConfidence(0.8d);
        Pipeline pipeline = createPipeline();
        orchestrator = new RAGOrchestrator(pipeline);

        NextStepRecommendation recommendation = NextStepRecommendation.builder()
            .intent("view_billing_history")
            .confidence(0.75)
            .query("Show my billing history")
            .build();
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("update_payment_method")
            .nextStepRecommended(recommendation)
            .build();

        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(actionHandlerRegistry.findHandler("update_payment_method")).thenReturn(Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(true);
        when(actionHandler.requiresConfirmation()).thenReturn(true);
        when(actionHandler.getConfirmationMessage(any(), any())).thenReturn("Confirm?");
        when(actionHandlerRegistry.findMetadata("update_payment_method")).thenReturn(Optional.empty());
        when(actionHandler.executeAction(any(), any()))
            .thenReturn(ActionResult.builder().success(true).message("Updated").build());

        OrchestrationResult result = orchestrator.orchestrate("Update my payment method", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getNextSteps()).containsExactly(recommendation);
        assertThat(result.getSmartSuggestion()).isEmpty();
        verify(ragProvider, never()).performRag(any(RAGRequest.class));
    }

    @Test
    void shouldNotInvokeSmartSuggestionsWhenDisabled() {
        // Recreate pipeline with updated properties
        smartSuggestionsProperties.setEnabled(false);
        Pipeline pipeline = createPipeline();
        orchestrator = new RAGOrchestrator(pipeline);

        NextStepRecommendation recommendation = NextStepRecommendation.builder()
            .intent("view_billing_history")
            .confidence(0.9)
            .query("Show my billing history")
            .build();
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("update_payment_method")
            .nextStepRecommended(recommendation)
            .build();

        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder().intents(List.of(intent)).build());
        when(actionHandlerRegistry.findHandler("update_payment_method")).thenReturn(Optional.of(actionHandler));
        when(actionHandler.validateActionAllowed(any())).thenReturn(true);
        when(actionHandler.requiresConfirmation()).thenReturn(true);
        when(actionHandler.getConfirmationMessage(any(), any())).thenReturn("Confirm?");
        when(actionHandlerRegistry.findMetadata("update_payment_method")).thenReturn(Optional.empty());
        when(actionHandler.executeAction(any(), any()))
            .thenReturn(ActionResult.builder().success(true).message("Updated").build());

        OrchestrationResult result = orchestrator.orchestrate("Update my payment method", com.ai.infrastructure.intent.orchestration.OrchestrationContext.forUser("user"));

        assertThat(result.getNextSteps()).containsExactly(recommendation);
        assertThat(result.getSmartSuggestion()).isEmpty();
        verify(ragProvider, never()).performRag(any(RAGRequest.class));
    }
}
