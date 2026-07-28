package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorDecision;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorRule;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import ai.fabric.intent.action.confirmation.ConfirmationInterceptorTrigger;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepPendingConfirmationLoopBreakerTest {

    @Test
    void shouldTrustStoredPendingActionParamsWhenUserConfirmsWithPlainYes() {
        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();

        AtomicReference<Map<String, Object>> executedParams = new AtomicReference<>();

        AIActionHandler handler = new AIActionHandler() {
            @Override
            public AIActionMetaData getActionMetadata() {
                return AIActionMetaData.builder()
                    .name("add_to_cart")
                    .accessMode(ActionAccessMode.READ_WRITE)
                    .description("Add sku to cart")
                    .requiredParameters(Set.of("sku"))
                    .build();
            }

            @Override
            public boolean requiresConfirmation() {
                return true;
            }

            @Override
            public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
                Object sku = params != null ? params.get("sku") : null;
                return "Add " + (sku != null ? sku.toString() : "unknown") + " to your cart?";
            }

            @Override
            public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
                executedParams.set(params);
                return ActionResult.builder()
                    .success(true)
                    .message("Added to cart")
                    .data(ActionPayload.object(Map.of("ok", true)))
                    .build();
            }
        };

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("add_to_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("add_to_cart")).thenReturn(Optional.of(handler.getActionMetadata()));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf((RAGProvider) null),
            mock(AICoreService.class),
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
            pendingActionStore,
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("chat-confirm-with-stored-params")
            .build();

        Intent actionIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("add_to_cart")
            .actionParams(Map.of("sku", "SKU-1"))
            .build();

        PipelineContext turn1 = PipelineContext.from("add SKU-1 to cart", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(actionIntent)).build())
            .build();

        OrchestrationResult turn1Result = step.process(turn1).getIntentResult();
        assertThat(turn1Result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(pendingActionStore.peekPendingAction("chat-confirm-with-stored-params", "user-1")).isPresent();

        Intent confirmIntent = Intent.builder()
            .type(IntentType.CONFIRMATION_POSITIVE)
            .build();
        PipelineContext turn2 = PipelineContext.from("Yes, confirm", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(confirmIntent)).build())
            .build();

        OrchestrationResult turn2Result = step.process(turn2).getIntentResult();

        assertThat(turn2Result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(turn2Result.isSuccess()).isTrue();
        assertThat(pendingActionStore.peekPendingAction("chat-confirm-with-stored-params", "user-1")).isEmpty();
        assertThat(executedParams.get()).containsEntry("sku", "SKU-1");
    }

    @Test
    void shouldExecutePendingActionWhenLlmReissuesSameActionInsteadOfConfirmation() {
        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();

        AtomicInteger executeCount = new AtomicInteger();

        AIActionHandler handler = new AIActionHandler() {
            @Override
            public AIActionMetaData getActionMetadata() {
                return AIActionMetaData.builder()
                    .name("add_to_cart")
                    .accessMode(ActionAccessMode.READ_WRITE)
                    .description("Add sku to cart")
                    .requiredParameters(Set.of("sku"))
                    .build();
            }

            @Override
            public boolean requiresConfirmation() {
                return true;
            }

            @Override
            public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
                Object sku = params != null ? params.get("sku") : null;
                return "Add 1 × " + (sku != null ? sku.toString() : "unknown") + " to your cart?";
            }

            @Override
            public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
                executeCount.incrementAndGet();
                return ActionResult.builder()
                    .success(true)
                    .message("Added to cart")
                    .data(ActionPayload.object(Map.of("ok", true)))
                    .build();
            }
        };

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("add_to_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("add_to_cart")).thenReturn(Optional.of(handler.getActionMetadata()));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("{\"decision\":\"POSITIVE\",\"confidence\":1.0}")
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
            pendingActionStore,
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("chat-1")
            .build();

        Intent actionIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("add_to_cart")
            .actionParams(Map.of("sku", "SKU-1"))
            .build();

        // Turn 1: user asks to add an explicit SKU -> confirmation required (pending action pushed).
        PipelineContext turn1 = PipelineContext.from("add SKU-1 to cart", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(actionIntent)).build())
            .build();

        OrchestrationResult turn1Result = step.process(turn1).getIntentResult();
        assertThat(turn1Result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(pendingActionStore.peekPendingAction("chat-1", "user-1")).isPresent();
        assertThat(executeCount.get()).isEqualTo(0);

        // Turn 2: user confirms, but the LLM incorrectly re-issues the ACTION again.
        PipelineContext turn2 = PipelineContext.from("Yes, confirm", orchContext)
            .toBuilder()
            .historyMessages(List.of(AIChatMessage.user("add SKU-1 to cart")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(actionIntent)).build())
            .build();

        OrchestrationResult turn2Result = step.process(turn2).getIntentResult();
        assertThat(turn2Result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(turn2Result.isSuccess()).isTrue();
        assertThat(executeCount.get()).isEqualTo(1);
        assertThat(pendingActionStore.peekPendingAction("chat-1", "user-1")).isEmpty();

        verify(aiCoreService, times(1)).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void shouldApplyConfiguredRetentionInterceptorsWhenLoopBreakerResolvesConfirmation() {
        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();

        AtomicInteger cancelExecuteCount = new AtomicInteger();
        AtomicInteger discountExecuteCount = new AtomicInteger();

        AIActionHandler cancelHandler = new AIActionHandler() {
            @Override
            public AIActionMetaData getActionMetadata() {
                return AIActionMetaData.builder()
                    .name("cancel_purchase_order")
                    .accessMode(ActionAccessMode.READ_WRITE)
                    .description("Cancel purchase order")
                    .requiredParameters(Set.of("orderNumber"))
                    .build();
            }

            @Override
            public boolean requiresConfirmation() {
                return true;
            }

            @Override
            public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
                return "Cancel this order?";
            }

            @Override
            public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
                cancelExecuteCount.incrementAndGet();
                return ActionResult.builder()
                    .success(true)
                    .message("Order cancelled.")
                    .data(ActionPayload.object(Map.of("orderNumber", params.get("orderNumber"))))
                    .build();
            }
        };

        AIActionHandler discountHandler = new AIActionHandler() {
            @Override
            public AIActionMetaData getActionMetadata() {
                return AIActionMetaData.builder()
                    .name("offer_order_discount")
                    .accessMode(ActionAccessMode.READ_WRITE)
                    .description("Offer discount")
                    .requiredParameters(Set.of("orderNumber"))
                    .build();
            }

            @Override
            public boolean requiresConfirmation() {
                return true;
            }

            @Override
            public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
                return "Apply discount?";
            }

            @Override
            public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
                discountExecuteCount.incrementAndGet();
                return ActionResult.builder()
                    .success(true)
                    .message("Discount offered.")
                    .data(ActionPayload.object(Map.of(
                        "orderNumber", params.get("orderNumber"),
                        "discountPercent", params.getOrDefault("discountPercent", 10)
                    )))
                    .build();
            }
        };

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("cancel_purchase_order")).thenReturn(Optional.of(cancelHandler));
        when(registry.findMetadata("cancel_purchase_order")).thenReturn(Optional.of(cancelHandler.getActionMetadata()));
        when(registry.findHandler("offer_order_discount")).thenReturn(Optional.of(discountHandler));
        when(registry.findMetadata("offer_order_discount")).thenReturn(Optional.of(discountHandler.getActionMetadata()));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("{\"decision\":\"POSITIVE\",\"confidence\":1.0}")
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
            pendingActionStore,
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        ReflectionTestUtils.setField(step, "confirmationInterceptorCatalogProvider", providerOf(rulesProvider(retentionRules())));

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("chat-retention")
            .build();

        Intent cancelIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_purchase_order")
            .actionParams(Map.of("orderNumber", "PO-1"))
            .build();

        PipelineContext turn1 = PipelineContext.from("cancel purchase order PO-1", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(cancelIntent)).build())
            .build();

        OrchestrationResult turn1Result = step.process(turn1).getIntentResult();
        assertThat(turn1Result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(((Map<?, ?>) turn1Result.getData()).get("action")).isEqualTo("cancel_purchase_order");

        PipelineContext turn2 = PipelineContext.from("yes", orchContext)
            .toBuilder()
            .historyMessages(List.of(AIChatMessage.user("cancel purchase order PO-1")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(cancelIntent)).build())
            .build();

        OrchestrationResult turn2Result = step.process(turn2).getIntentResult();
        assertThat(turn2Result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(((Map<?, ?>) turn2Result.getData()).get("action")).isEqualTo("offer_order_discount");
        assertThat(cancelExecuteCount.get()).isEqualTo(0);
        assertThat(discountExecuteCount.get()).isEqualTo(0);
        assertThat(pendingActionStore.getPendingActionStack("chat-retention", "user-1"))
            .extracting(PendingAction::action)
            .containsExactly("offer_order_discount", "cancel_purchase_order");

        Intent discountIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("offer_order_discount")
            .actionParams(Map.of("orderNumber", "PO-1", "discountPercent", 10))
            .build();

        PipelineContext turn3 = PipelineContext.from("yes", orchContext)
            .toBuilder()
            .historyMessages(List.of(AIChatMessage.user("cancel purchase order PO-1"), AIChatMessage.user("yes")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(discountIntent)).build())
            .build();

        OrchestrationResult turn3Result = step.process(turn3).getIntentResult();
        assertThat(turn3Result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(((Map<?, ?>) turn3Result.getData()).get("action")).isEqualTo("offer_order_discount");
        assertThat(cancelExecuteCount.get()).isEqualTo(0);
        assertThat(discountExecuteCount.get()).isEqualTo(1);
        assertThat(pendingActionStore.getPendingActionStack("chat-retention", "user-1")).isEmpty();
    }

    @Test
    void shouldHonorConfiguredOnceParamRegardlessOfStoredKeyCaseDuringLoopBreakerFlow() {
        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();

        AtomicInteger cancelExecuteCount = new AtomicInteger();
        AtomicInteger discountExecuteCount = new AtomicInteger();

        AIActionHandler cancelHandler = new AIActionHandler() {
            @Override
            public AIActionMetaData getActionMetadata() {
                return AIActionMetaData.builder()
                    .name("cancel_purchase_order")
                    .accessMode(ActionAccessMode.READ_WRITE)
                    .description("Cancel purchase order")
                    .requiredParameters(Set.of("orderNumber"))
                    .build();
            }

            @Override
            public boolean requiresConfirmation() {
                return true;
            }

            @Override
            public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
                return "Cancel this order?";
            }

            @Override
            public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
                cancelExecuteCount.incrementAndGet();
                return ActionResult.builder()
                    .success(true)
                    .message("Order cancelled.")
                    .data(ActionPayload.object(Map.of("orderNumber", params.get("orderNumber"))))
                    .build();
            }
        };

        AIActionHandler discountHandler = new AIActionHandler() {
            @Override
            public AIActionMetaData getActionMetadata() {
                return AIActionMetaData.builder()
                    .name("offer_order_discount")
                    .accessMode(ActionAccessMode.READ_WRITE)
                    .description("Offer discount")
                    .requiredParameters(Set.of("orderNumber"))
                    .build();
            }

            @Override
            public boolean requiresConfirmation() {
                return true;
            }

            @Override
            public String getConfirmationMessage(Map<String, Object> params, ActionContext context) {
                return "Apply discount?";
            }

            @Override
            public ActionResult executeAction(Map<String, Object> params, ActionContext context) {
                discountExecuteCount.incrementAndGet();
                return ActionResult.builder()
                    .success(true)
                    .message("Discount offered.")
                    .data(ActionPayload.object(Map.of(
                        "orderNumber", params.get("orderNumber"),
                        "discountPercent", params.getOrDefault("discountPercent", 10)
                    )))
                    .build();
            }
        };

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("cancel_purchase_order")).thenReturn(Optional.of(cancelHandler));
        when(registry.findMetadata("cancel_purchase_order")).thenReturn(Optional.of(cancelHandler.getActionMetadata()));
        when(registry.findHandler("offer_order_discount")).thenReturn(Optional.of(discountHandler));
        when(registry.findMetadata("offer_order_discount")).thenReturn(Optional.of(discountHandler.getActionMetadata()));

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.ORCHESTRATION)))
            .thenReturn(AIGenerationResponse.builder()
                .content("{\"decision\":\"POSITIVE\",\"confidence\":1.0}")
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
            pendingActionStore,
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );
        ReflectionTestUtils.setField(step, "confirmationInterceptorCatalogProvider", providerOf(rulesProvider(retentionRulesWithMixedCaseOnceParam())));

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("chat-retention-guard")
            .build();

        pendingActionStore.pushPendingAction("chat-retention-guard", "user-1", new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-1", "_RetentionOfferOffered", true),
            null,
            java.time.Instant.now()
        ));

        Intent cancelIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("cancel_purchase_order")
            .actionParams(Map.of("orderNumber", "PO-1"))
            .build();

        PipelineContext turn = PipelineContext.from("yes", orchContext)
            .toBuilder()
            .historyMessages(List.of(AIChatMessage.user("cancel purchase order PO-1")))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(cancelIntent)).build())
            .build();

        OrchestrationResult result = step.process(turn).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(((Map<?, ?>) result.getData()).get("action")).isEqualTo("cancel_purchase_order");
        assertThat(cancelExecuteCount.get()).isEqualTo(1);
        assertThat(discountExecuteCount.get()).isEqualTo(0);
        assertThat(pendingActionStore.getPendingActionStack("chat-retention-guard", "user-1")).isEmpty();
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private ConfirmationInterceptorCatalogProvider rulesProvider(List<ConfirmationInterceptorRule> rules) {
        return () -> rules;
    }

    private List<ConfirmationInterceptorRule> retentionRules() {
        return List.of(
            new ConfirmationInterceptorRule(
                "cancel_to_retention_offer",
                new ConfirmationInterceptorTrigger(
                    List.of("cancel_purchase_order"),
                    IntentType.CONFIRMATION_POSITIVE,
                    "_retentionOfferOffered"
                ),
                new ConfirmationInterceptorDecision(
                    ConfirmationInterceptorDecisionType.PROMPT_ACTION,
                    "offer_order_discount",
                    Map.of(
                        "orderNumber", "{{pending.actionParams.orderNumber}}",
                        "discountPercent", 10
                    ),
                    null
                ),
                ConfirmationInterceptorStackPolicy.NONE
            ),
            new ConfirmationInterceptorRule(
                "accept_retention_offer",
                new ConfirmationInterceptorTrigger(
                    List.of("offer_order_discount"),
                    IntentType.CONFIRMATION_POSITIVE,
                    null
                ),
                new ConfirmationInterceptorDecision(
                    ConfirmationInterceptorDecisionType.EXECUTE_ACTION,
                    "offer_order_discount",
                    Map.of(
                        "orderNumber", "{{pending.actionParams.orderNumber}}",
                        "discountPercent", "{{pending.actionParams.discountPercent|10}}"
                    ),
                    null
                ),
                new ConfirmationInterceptorStackPolicy(true, List.of("cancel_purchase_order"))
            )
        );
    }

    private List<ConfirmationInterceptorRule> retentionRulesWithMixedCaseOnceParam() {
        return List.of(
            new ConfirmationInterceptorRule(
                "cancel_to_retention_offer",
                new ConfirmationInterceptorTrigger(
                    List.of("cancel_purchase_order"),
                    IntentType.CONFIRMATION_POSITIVE,
                    "_RetentionOfferOffered"
                ),
                new ConfirmationInterceptorDecision(
                    ConfirmationInterceptorDecisionType.PROMPT_ACTION,
                    "offer_order_discount",
                    Map.of(
                        "orderNumber", "{{pending.actionParams.orderNumber}}",
                        "discountPercent", 10
                    ),
                    null
                ),
                ConfirmationInterceptorStackPolicy.NONE
            )
        );
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
