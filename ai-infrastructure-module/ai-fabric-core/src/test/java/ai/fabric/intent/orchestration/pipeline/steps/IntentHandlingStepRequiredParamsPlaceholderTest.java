package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepRequiredParamsPlaceholderTest {

    @Test
    void shouldTreatMetadataDescriptionCopiedIntoParamsAsMissing() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("change_delivery_address")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("change_delivery_address")
            .description("Change delivery address")
            .category("commerce")
            .parameters(Map.of(
                "shippingAddress", "New shipping address (required)"
            ))
            .requiredParameters(Set.of("shippingAddress"))
            .build();
        when(registry.findMetadata("change_delivery_address")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        // Simulate the extractor "filling" the missing value by copying the parameter description.
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("change_delivery_address")
            .actionParams(Map.of(
                "shippingAddress", "New shipping address (required)"
            ))
            .build();

        PipelineContext context = PipelineContext.from("change my address", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("missingRequiredParameters", List.of("shippingAddress"));
    }

    @Test
    void shouldTreatInstructionLikeValueAsMissing() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("change_delivery_address")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("change_delivery_address")
            .description("Change delivery address")
            .category("commerce")
            .parameters(Map.of(
                "shippingAddress", "New shipping address (required)"
            ))
            .requiredParameters(Set.of("shippingAddress"))
            .build();
        when(registry.findMetadata("change_delivery_address")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("change_delivery_address")
            .actionParams(Map.of(
                "shippingAddress", "New shipping address required"
            ))
            .build();

        PipelineContext context = PipelineContext.from("change my address", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("missingRequiredParameters", List.of("shippingAddress"));
    }

    @Test
    void shouldTreatEchoedUserQueryAsMissing() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("confirmable_echo")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("confirmable_echo")
            .description("Echo a message back")
            .category("test")
            .parameters(Map.of(
                "message", "Text to echo back (required)"
            ))
            .requiredParameters(Set.of("message"))
            .build();
        when(registry.findMetadata("confirmable_echo")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        String userMessage = "Use action 'confirmable_echo'.";
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("confirmable_echo")
            .actionParams(Map.of(
                "message", userMessage
            ))
            .build();

        PipelineContext context = PipelineContext.from(userMessage, OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("missingRequiredParameters", List.of("message"));
    }

    @Test
    void shouldAllowWholeUserMessageAsParamValueWhenItDoesNotLookLikeInstruction() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("change_delivery_address")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(ActionResult.builder().success(true).message("ok").build());

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("change_delivery_address")
            .description("Change delivery address")
            .category("commerce")
            .parameters(Map.of(
                "shippingAddress", "New shipping address (required)"
            ))
            .requiredParameters(Set.of("shippingAddress"))
            .build();
        when(registry.findMetadata("change_delivery_address")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        // Simulate a single-value follow-up reply (email/address/etc.) where the entire user message is the value.
        String userMessage = "16 dairy drive B2";
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("change_delivery_address")
            .actionParams(Map.of(
                "shippingAddress", userMessage
            ))
            .build();

        PipelineContext context = PipelineContext.from(userMessage, OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    void shouldExecuteActionWhenRequiredParamIsPresentInPinnedEvidence() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("create_purchase_order")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(ActionResult.builder().success(true).message("ok").build());

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("create_purchase_order")
            .description("Create purchase order")
            .category("commerce")
            .parameters(Map.of(
                "sku", "Product SKU (required)"
            ))
            .requiredParameters(Set.of("sku"))
            .build();
        when(registry.findMetadata("create_purchase_order")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        // Simulate the extractor proposing the param from pinned evidence (attachments/targets).
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("create_purchase_order")
            .actionParams(Map.of("sku", "ELEC-PHONE-002"))
            .build();

        ResolvedTarget target = ResolvedTarget.builder()
            .id("78")
            .vectorSpace("product")
            .metadata(Map.of("sku", "ELEC-PHONE-002"))
            .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
            .build();

        PipelineContext context = PipelineContext.from("buy it", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(target))
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        verify(handler).executeAction(org.mockito.ArgumentMatchers.argThat(map ->
            map != null && "ELEC-PHONE-002".equals(map.get("sku"))
        ), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldReturnClarificationRequiredWhenRequiredParamHasNoProvenance() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("create_purchase_order")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("create_purchase_order")
            .description("Create purchase order")
            .category("commerce")
            .parameters(Map.of(
                "sku", "Product SKU (required)"
            ))
            .requiredParameters(Set.of("sku"))
            .build();
        when(registry.findMetadata("create_purchase_order")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        // Extractor proposes a value, but it's not present in the user text or pinned evidence.
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("create_purchase_order")
            .actionParams(Map.of("sku", "ELEC-PHONE-002"))
            .build();

        PipelineContext context = PipelineContext.from("buy it", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("78")
                .vectorSpace("product")
                .metadata(Map.of("sku", "DIFFERENT-SKU"))
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("missingRequiredParameters", List.of("sku"));
    }

    @Test
    void shouldNotExposeSystemContextParamWhenTrustedSessionIsMissing() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("commerce_update_cart")
            .description("Update commerce cart")
            .category("commerce")
            .anonymousAllowed(true)
            .parameters(Map.of("shopperSessionId", "Bridge shopper session identifier (required)"))
            .requiredParameters(Set.of("shopperSessionId"))
            .build();
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("add this to my cart", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.getMessage()).contains("storefront session context");
        assertThat(result.toString()).doesNotContain("shopperSessionId");
        assertThat(result.getData()).containsEntry("missingRequiredParameters", List.of());
        verify(handler, org.mockito.Mockito.never()).executeAction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldNotExposeConfirmationAcceptedAsMissingUserParameter() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.getConfirmationMessage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn("Add Selling Plans Ski Wax to my cart?");

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("commerce_update_cart")
            .description("Update commerce cart")
            .category("commerce")
            .anonymousAllowed(true)
            .confirmationRequired(true)
            .parameters(Map.of(
                "cart_update_confirmation", "Presentation-only shopper-facing confirmation phrase",
                "confirmationAccepted", "Explicit shopper confirmation flag"
            ))
            .requiredParameters(Set.of("confirmationAccepted"))
            .build();
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of("cart_update_confirmation", "Add Selling Plans Ski Wax to my cart"))
            .build();

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-cart-confirmation")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(result.getMessage()).isEqualTo("Add Selling Plans Ski Wax to my cart?");
        assertThat(result.getData()).containsEntry(
            "providedParameters",
            Map.of("cart_update_confirmation", "Add Selling Plans Ski Wax to my cart")
        );
        assertThat(result.toString()).doesNotContain("confirmationAccepted");
        assertThat(pendingActionStore.peekPendingAction("chat-cart-confirmation", "user")).isPresent();
        verify(handler, org.mockito.Mockito.never()).executeAction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldInjectShopperSessionIdFromTrustedRuntimeContext() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(ActionResult.builder().success(true).message("Cart update queued.").build());

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("commerce_update_cart")
            .description("Update commerce cart")
            .category("commerce")
            .anonymousAllowed(true)
            .parameters(Map.of(
                "shopperSessionId", "Bridge shopper session identifier (required)",
                "add_items", "Items to add (optional)"
            ))
            .requiredParameters(Set.of("shopperSessionId"))
            .build();
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of("add_items", List.of(Map.of("product_variant_id", "commerce://product-variant/1", "quantity", 1))))
            .build();

        PipelineContext context = PipelineContext.from("add this to my cart", OrchestrationContext.forSession("shopper-session-123"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        verify(handler).executeAction(org.mockito.ArgumentMatchers.argThat(map ->
            map != null && "shopper-session-123".equals(map.get("shopperSessionId"))
        ), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void shouldPopulateSnakeCaseRequiredParamFromAttachmentMetadataAlias() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        AIActionHandler handler = mock(AIActionHandler.class);
        when(registry.findHandler("commerce_get_product_details")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenReturn(ActionResult.builder().success(true).message("Product details loaded.").build());

        AIActionMetaData meta = AIActionMetaData.builder()
            .name("commerce_get_product_details")
            .description("Get commerce product details")
            .category("commerce")
            .anonymousAllowed(true)
            .parameters(Map.of("product_id", "commerce product ID"))
            .requiredParameters(Set.of("product_id"))
            .build();
        when(registry.findMetadata("commerce_get_product_details")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = new IntentHandlingStep(
            registry,
            providerOf(mock(RAGProvider.class)),
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
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_get_product_details")
            .actionParams(Map.of())
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.forSession("shopper-session-123").toBuilder()
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .source("commerce-storefront-context")
                .metadata(Map.of("productId", "commerce://resource/Product/7939426025555"))
                .contentText("Current product: Nimbus Air 13 Laptop.")
                .build()))
            .build();
        PipelineContext context = PipelineContext.from("What is this product built for?", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        verify(handler).executeAction(org.mockito.ArgumentMatchers.argThat(map ->
            map != null && "commerce://resource/Product/7939426025555".equals(map.get("product_id"))
        ), org.mockito.ArgumentMatchers.any());
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
