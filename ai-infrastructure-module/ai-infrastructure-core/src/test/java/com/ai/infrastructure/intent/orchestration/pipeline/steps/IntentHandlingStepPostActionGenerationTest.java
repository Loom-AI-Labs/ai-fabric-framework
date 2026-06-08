package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.LlmPurpose;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionHandler;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
import com.ai.infrastructure.intent.vectorspace.RankBasedMerger;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.spi.AdvancedRAGProvider;
import com.ai.infrastructure.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepPostActionGenerationTest {

    @Test
    void shouldGeneratePostActionSummaryWhenHandlerProvidesFacts() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("test_action")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.getConfirmationMessage(any(), any())).thenReturn("Confirm?");

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("OK")
            .data(ActionResultContracts.object(Map.of("result", "value")))
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of(
            "status", "ok",
            "result", "value"
        )));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("Summary").model("test-model").build());

        PostActionGenerationProperties properties = new PostActionGenerationProperties();
        properties.setEnabled(true);

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            properties,
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("test_action")
            .requiresGeneration(true)
            .generationInstructions("Explain the result.")
            .actionParams(Map.of("x", "y"))
            .build();

        PipelineContext context = PipelineContext.from("Do the thing", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Summary");
        assertThat(result.getData()).containsKey("postActionGeneration");
        assertThat(result.getData()).containsKey("summary");

        verify(handler).executeAction(any(), any());
        verify(aiCoreService).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldForcePostActionGenerationForAllowedReadActionWhenIntentDidNotRequestGeneration() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("relationship_query")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .anonymousAllowed(true)
            .build();
        when(registry.findHandler("relationship_query")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("relationship_query")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Action executed.")
            .data(ActionResultContracts.object(Map.of(
                "success", true,
                "message", "Relationship query results",
                "data", Map.of(
                    "query", "Compare Liquid and Oxygen",
                    "documents", List.of(
                        Map.of("title", "Liquid", "metadata", Map.of("available", true, "price", "749.95")),
                        Map.of("title", "Oxygen", "metadata", Map.of("available", true, "price", "1025.00"))
                    ),
                    "totalResults", 2
                )
            )))
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("Liquid and Oxygen are both available; Liquid is lower priced based on the read-action evidence.")
                .model("test-model")
                .build());

        RelationshipQueryPostActionGenerationProperties relationshipProperties = new RelationshipQueryPostActionGenerationProperties();
        relationshipProperties.setEnabled(false);

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            relationshipProperties,
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("relationship_query")
            .requiresGeneration(false)
            .actionParams(Map.of("query", "Compare Liquid and Oxygen"))
            .build();

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "thinker",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(false, true, true, false, false, false, true, false, false, true, true, false),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE,
                List.of("relationship_query"),
                true,
                2,
                2,
                3,
                1,
                4000,
                2400,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.PARALLEL_ACTIONS_AND_RAG,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );

        PipelineContext context = PipelineContext.from("Compare Liquid and Oxygen", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("Liquid and Oxygen are both available");
        assertThat(result.getMessage()).doesNotContain("Action executed");
        assertThat(result.getData()).containsKey("postActionGeneration");

        verify(handler).executeAction(any(), any());
        ArgumentCaptor<AIGenerationRequest> generationRequest = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(generationRequest.capture(), eq(LlmPurpose.GENERATION));
        assertThat(generationRequest.getValue().getPrompt())
            .contains("Do not treat presence, status, or availability alone as safety");
    }

    @Test
    void shouldForceGenericPostActionGenerationForGroundingEligibleReadAction() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("search_products")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .anonymousAllowed(true)
            .build();
        when(registry.findHandler("search_products")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("search_products")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Action executed.")
            .data(ActionResultContracts.object(Map.of("items", List.of(
                Map.of("title", "Liquid", "available", true, "price", "749.95"),
                Map.of("title", "Oxygen", "available", true, "price", "1025.00")
            ))))
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of(
            "products", List.of(
                Map.of("title", "Liquid", "available", true, "price", "749.95"),
                Map.of("title", "Oxygen", "available", true, "price", "1025.00")
            )
        )));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("Liquid is available and lower priced than Oxygen based on the read-action facts.")
                .model("test-model")
                .build());

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("search_products")
            .requiresGeneration(false)
            .actionParams(Map.of("query", "Compare Liquid and Oxygen"))
            .build();

        PipelineContext context = PipelineContext.from("Compare Liquid and Oxygen", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .orchestrationPolicy(forceGroundingEligibleReadActionPostGenerationPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).contains("Liquid is available");
        assertThat(result.getMessage()).doesNotContain("Action executed");
        assertThat(result.getData()).containsKey("postActionGeneration");

        verify(handler).executeAction(any(), any());
        verify(aiCoreService).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldUseDeterministicFallbackForForcedGroundingEligibleReadActionWhenGenerationFails() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        AIActionMetaData metadata = AIActionMetaData.builder()
            .name("example_catalog_search")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .anonymousAllowed(true)
            .build();
        when(registry.findHandler("example_catalog_search")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("example_catalog_search")).thenReturn(Optional.of(metadata));
        when(handler.validateActionAllowed(any())).thenReturn(true);

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Action executed.")
            .data(ActionResultContracts.object(Map.of(
                "adapterType", "mcp-tool",
                "mcpToolName", "example.catalog.search",
                "toolResult", Map.of(
                    "content", List.of(Map.of(
                        "type", "text",
                        "text", """
                            {
                              "status": "OK",
                              "tool": "example.catalog.search",
                              "categories": [
                                {"name": "Validation"},
                                {"name": "Launch Readiness"}
                              ],
                              "packageTemplates": [
                                {"name": "SaaS Launch Readiness"}
                              ]
                            }
                            """
                    )),
                    "isError", false
                )
            )))
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of(
            "success", true,
            "message", "Action executed."
        )));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenThrow(new RuntimeException("provider down"));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("example_catalog_search")
            .requiresGeneration(false)
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("Give me services in details", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .orchestrationPolicy(forceGroundingEligibleReadActionPostGenerationPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage())
            .contains("grounded evidence")
            .doesNotContain("Action executed");
        assertThat(result.getData())
            .containsEntry("summary", result.getMessage())
            .containsEntry("answer", result.getMessage());
        @SuppressWarnings("unchecked")
        Map<String, Object> generationMetadata = (Map<String, Object>) result.getData().get("postActionGeneration");
        assertThat(generationMetadata)
            .containsEntry("used", false)
            .containsEntry("skippedReason", "generation_failed")
            .containsEntry("deterministicFallbackUsed", true);

        verify(handler).executeAction(any(), any());
        ArgumentCaptor<AIGenerationRequest> generationRequest = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(generationRequest.capture(), eq(LlmPurpose.GENERATION));
        assertThat(generationRequest.getValue().getPrompt())
            .contains("actionResultData")
            .contains("example.catalog.search")
            .contains("SaaS Launch Readiness");
    }

    @Test
    void shouldSkipPostActionGenerationWhenHandlerOptsOut() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("test_action")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("OK")
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.empty());

        AICoreService aiCoreService = mock(AICoreService.class);

        PostActionGenerationProperties properties = new PostActionGenerationProperties();
        properties.setEnabled(true);

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            properties,
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("test_action")
            .requiresGeneration(true)
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("Do the thing", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("OK");

        verify(aiCoreService, never()).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldNotFailActionWhenPostActionGenerationThrows() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("test_action")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("OK")
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of("status", "ok")));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenThrow(new RuntimeException("provider down"));

        PostActionGenerationProperties properties = new PostActionGenerationProperties();
        properties.setEnabled(true);

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            properties,
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("test_action")
            .requiresGeneration(true)
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("Do the thing", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("OK");
        assertThat(result.getData()).containsKey("postActionGeneration");
    }

    @Test
    void shouldSkipPostActionGenerationWhenHandlerFactsBuilderThrows() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("test_action")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("OK")
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any()))
            .thenThrow(new RuntimeException("oops"));

        AICoreService aiCoreService = mock(AICoreService.class);

        PostActionGenerationProperties properties = new PostActionGenerationProperties();
        properties.setEnabled(true);

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            properties,
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("test_action")
            .requiresGeneration(true)
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("Do the thing", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("OK");
        assertThat(result.getData()).containsKey("postActionGeneration");

        verify(aiCoreService, never()).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldNotReplaceActionMessageWhenGenerationReturnsEmptyContent() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("test_action")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(any())).thenReturn(true);

        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("OK")
            .build();
        when(handler.executeAction(any(), any())).thenReturn(actionResult);
        when(handler.buildPostActionLlmFacts(eq(actionResult), any())).thenReturn(Optional.of(Map.of("status", "ok")));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION)))
            .thenReturn(AIGenerationResponse.builder().content("  ").build());

        PostActionGenerationProperties properties = new PostActionGenerationProperties();
        properties.setEnabled(true);

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            properties,
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("test_action")
            .requiresGeneration(true)
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("Do the thing", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("OK");
        assertThat(result.getData()).containsKey("postActionGeneration");
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }

    private OrchestrationPolicy forceGroundingEligibleReadActionPostGenerationPolicy() {
        return new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "thinker",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(
                false,
                true,
                true,
                false,
                false,
                false,
                true,
                false,
                false,
                true,
                true,
                false,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }
}
