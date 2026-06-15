package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionHandler;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionParamSchema;
import com.ai.infrastructure.intent.action.AIActionParamType;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.attachment.NormalizedAttachment;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTarget;
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
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepBatchTargetsTest {

    @Test
    void shouldExpandBatchTargetsFromResolvedTargetsWhenOnlyOneItemProvided() {
        AIActionMetaData meta = addToCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("ok")
            .data(ActionResultContracts.object(Map.of()))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("add_to_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("add_to_cart")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = newStep(registry);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("add_to_cart")
            .actionParams(Map.of(
                "items", List.of(Map.of("sku", "SKU-HP-31150", "quantity", 1))
            ))
            .build();

        PipelineContext context = PipelineContext.from("add to cart", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(
                target("p1", Map.of("sku", "SKU-HP-31150")),
                target("p2", Map.of("sku", "SKU-APP-83635"))
            ))
            .build();

        step.process(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).executeAction(paramsCaptor.capture(), any());

        Object raw = paramsCaptor.getValue().get("items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).hasSize(2);
        assertThat(items).anySatisfy(item -> assertThat(item).containsEntry("sku", "SKU-HP-31150"));
        assertThat(items).anySatisfy(item -> assertThat(item).containsEntry("sku", "SKU-APP-83635"));
    }

    @Test
    void shouldCoalesceMultipleBatchActionIntentsIntoSingleExecution() {
        AIActionMetaData meta = addToCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("ok")
            .data(ActionResultContracts.object(Map.of()))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("add_to_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("add_to_cart")).thenReturn(Optional.of(meta));

        IntentHandlingStep step = newStep(registry);

        Intent one = Intent.builder()
            .type(IntentType.ACTION)
            .action("add_to_cart")
            .actionParams(Map.of(
                "items", List.of(Map.of("sku", "SKU-HP-31150", "quantity", 1))
            ))
            .build();
        Intent two = Intent.builder()
            .type(IntentType.ACTION)
            .action("add_to_cart")
            .actionParams(Map.of(
                "items", List.of(Map.of("sku", "SKU-APP-83635", "quantity", 1))
            ))
            .build();

        PipelineContext context = PipelineContext.from("add to cart", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(one, two)).build())
            .resolvedTargets(List.of(
                target("p1", Map.of("sku", "SKU-HP-31150")),
                target("p2", Map.of("sku", "SKU-APP-83635"))
            ))
            .build();

        step.process(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).executeAction(paramsCaptor.capture(), any());

        Object raw = paramsCaptor.getValue().get("items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).hasSize(2);
    }

    @Test
    void shouldDefaultMcpCartAddItemsFromProductVariantMetadata() {
        AIActionMetaData meta = commerceCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("ok")
            .data(ActionResultContracts.object(Map.of()))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("add this to cart", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(
                target("commerce://resource/Product/1", Map.of("product_variant_id", "commerce://resource/ProductVariant/1"))
            ))
            .build();

        newStep(registry).process(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).executeAction(paramsCaptor.capture(), any());

        Object raw = paramsCaptor.getValue().get("add_items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/1")
            .containsEntry("quantity", 1L));
    }

    @Test
    void shouldReplaceInvalidBatchItemWithResolvedTargetMetadataWhenSchemaConstrained() {
        AIActionMetaData meta = commerceCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("ok")
            .data(ActionResultContracts.object(Map.of()))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of("product_variant_id", "Selling Plans Ski Wax", "quantity", "1"))
            ))
            .build();

        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(
                target("commerce://resource/Product/1", Map.of("product_variant_id", "commerce://resource/ProductVariant/1"))
            ))
            .build();

        newStep(registry).process(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).executeAction(paramsCaptor.capture(), any());

        Object raw = paramsCaptor.getValue().get("add_items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/1")
            .containsEntry("quantity", 1L));
    }

    @Test
    void shouldNotDefaultMcpCartAddItemWhenRequiredVariantMetadataIsMissing() {
        AIActionMetaData meta = commerceCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("ok")
            .data(ActionResultContracts.object(Map.of()))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of())
            .build();

        PipelineContext context = PipelineContext.from("add this to cart", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(
                target("commerce://resource/Product/1", Map.of("title", "The Minimal Snowboard"))
            ))
            .build();

        newStep(registry).process(context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).executeAction(paramsCaptor.capture(), any());

        assertThat(paramsCaptor.getValue()).doesNotContainKey("add_items");
    }

    @Test
    void shouldNotAskForConfirmationWhenMcpRequiredAnyArgumentsAreMissing() {
        AIActionMetaData meta = commerceCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        IntentHandlingStep step = newStep(registry, pendingActionStore);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of("cart_update_confirmation", "Add 1 Selling Plans Ski Wax to your cart"))
            .build();

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-missing-executable")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.getMessage()).contains("specific target");
        assertThat(pendingActionStore.peekPendingAction("chat-missing-executable", "user")).isEmpty();
        verify(handler, never()).getConfirmationMessage(anyMap(), any());
        verify(handler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldRejectEvidenceBoundMcpIdentifierThatWasNotProvidedByTrustedEvidence() {
        AIActionMetaData meta = commerceCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        IntentHandlingStep step = newStep(registry, pendingActionStore);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of(
                    "product_variant_id", "commerce://resource/ProductVariant/4371234567890",
                    "quantity", 1
                ))
            ))
            .build();

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-untrusted-id")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.getMessage()).contains("trusted selected item");
        assertThat(pendingActionStore.peekPendingAction("chat-untrusted-id", "user")).isEmpty();
        verify(handler, never()).getConfirmationMessage(anyMap(), any());
        verify(handler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldAllowEvidenceBoundMcpIdentifierWhenItMatchesTrustedTargetMetadata() {
        AIActionMetaData meta = commerceCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());
        when(handler.getConfirmationMessage(anyMap(), any())).thenReturn("Add 1 Selling Plans Ski Wax to your cart?");

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        IntentHandlingStep step = newStep(registry, pendingActionStore);

        String variantId = "commerce://resource/ProductVariant/44506675314771";
        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of(
                    "product_variant_id", variantId,
                    "quantity", 1
                ))
            ))
            .build();

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-trusted-id")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(target("commerce://resource/Product/7930570047571", Map.of("product_variant_id", variantId))))
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(result.getMessage()).isEqualTo("Add 1 Selling Plans Ski Wax to your cart?");
        assertThat(pendingActionStore.peekPendingAction("chat-trusted-id", "user")).isPresent();
        verify(handler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldPreserveTrustedEvidenceAcrossPendingConfirmation() {
        AIActionMetaData meta = commerceCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());
        when(handler.getConfirmationMessage(anyMap(), any())).thenReturn("Add 1 Selling Plans Ski Wax to your cart?");
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("Cart updated.")
            .data(ActionResultContracts.object(Map.of("status", "updated")))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(meta));

        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        IntentHandlingStep step = newStep(registry, pendingActionStore);

        String variantId = "commerce://resource/ProductVariant/44506675314771";
        Intent actionIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of(
                    "product_variant_id", variantId,
                    "quantity", 1
                ))
            ))
            .build();

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-trusted-confirm")
            .build();
        PipelineContext turn1 = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(actionIntent)).build())
            .resolvedTargets(List.of(target("commerce://resource/Product/7930570047571", Map.of("product_variant_id", variantId))))
            .build();

        OrchestrationResult turn1Result = step.process(turn1).getIntentResult();
        assertThat(turn1Result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(pendingActionStore.peekPendingAction("chat-trusted-confirm", "user")).isPresent();

        Intent confirmIntent = Intent.builder()
            .type(IntentType.CONFIRMATION_POSITIVE)
            .build();
        PipelineContext turn2 = PipelineContext.from("Yes, confirm", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(confirmIntent)).build())
            .build();

        OrchestrationResult turn2Result = step.process(turn2).getIntentResult();

        assertThat(turn2Result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(turn2Result.isSuccess()).isTrue();
        assertThat(pendingActionStore.peekPendingAction("chat-trusted-confirm", "user")).isEmpty();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).executeAction(paramsCaptor.capture(), any());
        assertThat(paramsCaptor.getValue()).containsKey("add_items");
    }

    @Test
    void shouldResolveInternalOwnedResourceParamFromTrustedAttachmentMetadata() {
        AIActionMetaData meta = commerceGetCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("cart")
            .data(ActionResultContracts.object(Map.of("status", "ok")))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_get_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_get_cart")).thenReturn(Optional.of(meta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_get_cart")
            .actionParams(Map.of())
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .sessionId("shopper-session-1")
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .id("storefront-context")
                .vectorSpace("commerce-storefront-context")
                .metadata(Map.of("cart_id", "commerce://resource/Cart/cart-1"))
                .source("storefront")
                .build()))
            .build();
        PipelineContext context = PipelineContext.from("what's in my cart?", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        assertThat(result.getType())
            .describedAs("result=%s", result)
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(handler, times(1)).executeAction(paramsCaptor.capture(), any());
        assertThat(paramsCaptor.getValue())
            .containsEntry("cart_id", "commerce://resource/Cart/cart-1")
            .containsEntry("shopperSessionId", "shopper-session-1");
    }

    @Test
    void shouldNotAskShopperForHiddenOwnedResourceParamWhenMissing() {
        AIActionMetaData meta = commerceGetCartMeta();
        AIActionHandler handler = mock(AIActionHandler.class);
        when(handler.validateActionAllowed(any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(false);

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_get_cart")).thenReturn(Optional.of(handler));
        when(registry.findMetadata("commerce_get_cart")).thenReturn(Optional.of(meta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_get_cart")
            .actionParams(Map.of())
            .build();
        PipelineContext context = PipelineContext.from(
                "what's in my cart?",
                OrchestrationContext.builder().sessionId("shopper-session-1").build()
            )
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.getMessage()).doesNotContain("cart_id");
        assertThat(result.getData()).containsEntry("missingRequiredParameters", List.of());
        assertThat(result.getData()).containsEntry("providedParameters", Map.of());
        verify(handler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldResolveActionParamFromAllowedReadActionBeforeWriteExecution() {
        AIActionMetaData returnMeta = requestReturnMeta();
        AIActionMetaData recentOrderMeta = mostRecentOrderMeta();
        AIActionHandler returnHandler = mock(AIActionHandler.class);
        AIActionHandler recentOrderHandler = mock(AIActionHandler.class);
        when(returnHandler.validateActionAllowed(any())).thenReturn(true);
        when(returnHandler.requiresConfirmation()).thenReturn(false);
        when(returnHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("return requested")
            .data(ActionResultContracts.object(Map.of("status", "requested")))
            .build());
        when(recentOrderHandler.validateActionAllowed(any())).thenReturn(true);
        when(recentOrderHandler.requiresConfirmation()).thenReturn(false);
        when(recentOrderHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("order found")
            .data(ActionResultContracts.object(Map.of("order_number", "#1001")))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_request_return")).thenReturn(Optional.of(returnHandler));
        when(registry.findMetadata("commerce_request_return")).thenReturn(Optional.of(returnMeta));
        when(registry.findHandler("commerce_get_most_recent_order_status")).thenReturn(Optional.of(recentOrderHandler));
        when(registry.findMetadata("commerce_get_most_recent_order_status")).thenReturn(Optional.of(recentOrderMeta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_request_return")
            .actionParams(Map.of())
            .build();
        PipelineContext context = PipelineContext.from("I want to return my last order", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(customerReadPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        verify(recentOrderHandler, times(1)).executeAction(anyMap(), any());
        assertThat(result.getType())
            .describedAs("result=%s", result)
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(returnHandler, times(1)).executeAction(paramsCaptor.capture(), any());
        assertThat(paramsCaptor.getValue()).containsEntry("order_number", "#1001");
    }

    @Test
    void shouldReplaceInvalidProvidedActionParamWithAllowedReadActionEvidence() {
        AIActionMetaData returnMeta = requestReturnMeta();
        AIActionMetaData recentOrderMeta = mostRecentOrderMeta();
        AIActionHandler returnHandler = mock(AIActionHandler.class);
        AIActionHandler recentOrderHandler = mock(AIActionHandler.class);
        when(returnHandler.validateActionAllowed(any())).thenReturn(true);
        when(returnHandler.requiresConfirmation()).thenReturn(false);
        when(returnHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("return requested")
            .data(ActionResultContracts.object(Map.of("status", "requested")))
            .build());
        when(recentOrderHandler.validateActionAllowed(any())).thenReturn(true);
        when(recentOrderHandler.requiresConfirmation()).thenReturn(false);
        when(recentOrderHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("order found")
            .data(ActionResultContracts.object(Map.of("order_number", "#1001")))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_request_return")).thenReturn(Optional.of(returnHandler));
        when(registry.findMetadata("commerce_request_return")).thenReturn(Optional.of(returnMeta));
        when(registry.findHandler("commerce_get_most_recent_order_status")).thenReturn(Optional.of(recentOrderHandler));
        when(registry.findMetadata("commerce_get_most_recent_order_status")).thenReturn(Optional.of(recentOrderMeta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_request_return")
            .actionParams(Map.of("order_number", "last"))
            .build();
        PipelineContext context = PipelineContext.from("I want to return my last order", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(customerReadPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        verify(recentOrderHandler, times(1)).executeAction(anyMap(), any());
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(returnHandler, times(1)).executeAction(paramsCaptor.capture(), any());
        assertThat(paramsCaptor.getValue()).containsEntry("order_number", "#1001");
    }

    @Test
    void shouldSurfaceReadActionFailureWhenResolvingDependentActionParam() {
        AIActionMetaData returnMeta = requestReturnMeta();
        AIActionMetaData recentOrderMeta = mostRecentOrderMeta();
        AIActionHandler returnHandler = mock(AIActionHandler.class);
        AIActionHandler recentOrderHandler = mock(AIActionHandler.class);
        when(returnHandler.validateActionAllowed(any())).thenReturn(true);
        when(returnHandler.requiresConfirmation()).thenReturn(false);
        when(recentOrderHandler.validateActionAllowed(any())).thenReturn(true);
        when(recentOrderHandler.requiresConfirmation()).thenReturn(false);
        when(recentOrderHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(false)
            .errorCode("CUSTOMER_ACCOUNT_AUTH_REQUIRED")
            .message("Customer account auth is required.")
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_request_return")).thenReturn(Optional.of(returnHandler));
        when(registry.findMetadata("commerce_request_return")).thenReturn(Optional.of(returnMeta));
        when(registry.findHandler("commerce_get_most_recent_order_status")).thenReturn(Optional.of(recentOrderHandler));
        when(registry.findMetadata("commerce_get_most_recent_order_status")).thenReturn(Optional.of(recentOrderMeta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_request_return")
            .actionParams(Map.of("order_number", "last"))
            .build();
        PipelineContext context = PipelineContext.from("I want to return my last order", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(customerReadPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        verify(recentOrderHandler, times(1)).executeAction(anyMap(), any());
        verify(returnHandler, never()).executeAction(anyMap(), any());
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("Customer account auth is required.");
        assertThat(result.getData()).containsEntry("action", "commerce_get_most_recent_order_status");
        assertThat(result.getData()).containsEntry("dependentAction", "commerce_request_return");
        Object actionResult = result.getData().get("actionResult");
        assertThat(actionResult).isInstanceOf(ActionResult.class);
        assertThat(((ActionResult) actionResult).getErrorCode()).isEqualTo("CUSTOMER_ACCOUNT_AUTH_REQUIRED");
    }

    @Test
    void shouldResolveCartAddItemFromAllowedCatalogReadActionBeforeConfirmation() {
        AIActionMetaData cartMeta = commerceCartMeta();
        AIActionMetaData catalogMeta = commerceSearchCatalogMeta();
        AIActionHandler cartHandler = mock(AIActionHandler.class);
        AIActionHandler catalogHandler = mock(AIActionHandler.class);
        when(cartHandler.validateActionAllowed(any())).thenReturn(true);
        when(cartHandler.requiresConfirmation()).thenReturn(true);
        when(cartHandler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());
        when(cartHandler.getConfirmationMessage(anyMap(), any())).thenReturn("Add 1 Selling Plans Ski Wax to your cart?");
        when(catalogHandler.validateActionAllowed(any())).thenReturn(true);
        when(catalogHandler.requiresConfirmation()).thenReturn(false);
        when(catalogHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("catalog search")
            .data(ActionResultContracts.object(Map.of("documents", List.of(Map.of(
                "title", "Selling Plans Ski Wax",
                "product_variant_id", "commerce://resource/ProductVariant/44506675314771"
            )))))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(cartHandler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(cartMeta));
        when(registry.findHandler("commerce_search_catalog")).thenReturn(Optional.of(catalogHandler));
        when(registry.findMetadata("commerce_search_catalog")).thenReturn(Optional.of(catalogMeta));

        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        IntentHandlingStep step = newStep(registry, pendingActionStore);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of("product_search_query", "Selling Plans Ski Wax"))
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-catalog-resolved-cart")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .orchestrationPolicy(cartReadPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(result.getMessage()).isEqualTo("Add 1 Selling Plans Ski Wax to your cart?");
        assertThat(pendingActionStore.peekPendingAction("chat-catalog-resolved-cart", "user")).isPresent();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> catalogParamsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(catalogHandler, times(1)).executeAction(catalogParamsCaptor.capture(), any());
        assertThat(catalogParamsCaptor.getValue()).containsEntry("query", "Selling Plans Ski Wax");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cartParamsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cartHandler, times(1)).getConfirmationMessage(cartParamsCaptor.capture(), any());
        Object raw = cartParamsCaptor.getValue().get("add_items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/44506675314771")
            .containsEntry("quantity", 1L));
        verify(cartHandler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldReplaceInvalidCartAddItemWithAllowedCatalogReadActionBeforeConfirmation() {
        AIActionMetaData cartMeta = commerceCartMeta();
        AIActionMetaData catalogMeta = commerceSearchCatalogMeta();
        AIActionHandler cartHandler = mock(AIActionHandler.class);
        AIActionHandler catalogHandler = mock(AIActionHandler.class);
        when(cartHandler.validateActionAllowed(any())).thenReturn(true);
        when(cartHandler.requiresConfirmation()).thenReturn(true);
        when(cartHandler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());
        when(cartHandler.getConfirmationMessage(anyMap(), any())).thenReturn("Add 1 Selling Plans Ski Wax to your cart?");
        when(catalogHandler.validateActionAllowed(any())).thenReturn(true);
        when(catalogHandler.requiresConfirmation()).thenReturn(false);
        when(catalogHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("catalog search")
            .data(ActionResultContracts.object(Map.of("documents", List.of(Map.of(
                "title", "Selling Plans Ski Wax",
                "product_variant_id", "commerce://resource/ProductVariant/44506675314771"
            )))))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(cartHandler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(cartMeta));
        when(registry.findHandler("commerce_search_catalog")).thenReturn(Optional.of(catalogHandler));
        when(registry.findMetadata("commerce_search_catalog")).thenReturn(Optional.of(catalogMeta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of(
                    "product_variant_id", "Selling Plans Ski Wax",
                    "quantity", 1
                ))
            ))
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-invalid-catalog-resolved-cart")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .orchestrationPolicy(cartReadPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cartParamsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cartHandler, times(1)).getConfirmationMessage(cartParamsCaptor.capture(), any());
        Object raw = cartParamsCaptor.getValue().get("add_items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/44506675314771")
            .containsEntry("quantity", 1L));
        verify(catalogHandler, times(1)).executeAction(anyMap(), any());
        verify(cartHandler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldResolveCartAddItemFromNestedSearchQueryBeforeConfirmation() {
        AIActionMetaData cartMeta = commerceCartMeta();
        AIActionMetaData catalogMeta = commerceSearchCatalogMeta();
        AIActionHandler cartHandler = mock(AIActionHandler.class);
        AIActionHandler catalogHandler = mock(AIActionHandler.class);
        when(cartHandler.validateActionAllowed(any())).thenReturn(true);
        when(cartHandler.requiresConfirmation()).thenReturn(true);
        when(cartHandler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());
        when(cartHandler.getConfirmationMessage(anyMap(), any())).thenReturn("Add 1 Selling Plans Ski Wax to your cart?");
        when(catalogHandler.validateActionAllowed(any())).thenReturn(true);
        when(catalogHandler.requiresConfirmation()).thenReturn(false);
        when(catalogHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("catalog search")
            .data(ActionResultContracts.object(Map.of("documents", List.of(Map.of(
                "title", "Selling Plans Ski Wax",
                "id", "commerce://resource/ProductVariant/44506675314771"
            )))))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(cartHandler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(cartMeta));
        when(registry.findHandler("commerce_search_catalog")).thenReturn(Optional.of(catalogHandler));
        when(registry.findMetadata("commerce_search_catalog")).thenReturn(Optional.of(catalogMeta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of(
                    "product_search_query", "Selling Plans Ski Wax",
                    "quantity", 1
                ))
            ))
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-nested-search-query-catalog-resolved-cart")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .orchestrationPolicy(cartReadPolicy())
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> catalogParamsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(catalogHandler, times(1)).executeAction(catalogParamsCaptor.capture(), any());
        assertThat(catalogParamsCaptor.getValue()).containsEntry("query", "Selling Plans Ski Wax");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cartParamsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cartHandler, times(1)).getConfirmationMessage(cartParamsCaptor.capture(), any());
        Object raw = cartParamsCaptor.getValue().get("add_items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/44506675314771")
            .containsEntry("quantity", 1L));
        verify(cartHandler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldResolveCartAddItemFromNestedSearchQueryWhenStorefrontContextTargetIsPresent() {
        AIActionMetaData cartMeta = commerceCartMeta();
        AIActionMetaData catalogMeta = commerceSearchCatalogMeta();
        AIActionHandler cartHandler = mock(AIActionHandler.class);
        AIActionHandler catalogHandler = mock(AIActionHandler.class);
        when(cartHandler.validateActionAllowed(any())).thenReturn(true);
        when(cartHandler.requiresConfirmation()).thenReturn(true);
        when(cartHandler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());
        when(cartHandler.getConfirmationMessage(anyMap(), any())).thenReturn("Add 1 Selling Plans Ski Wax to your cart?");
        when(catalogHandler.validateActionAllowed(any())).thenReturn(true);
        when(catalogHandler.requiresConfirmation()).thenReturn(false);
        when(catalogHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("catalog search")
            .data(ActionResultContracts.object(Map.of("documents", List.of(Map.of(
                "title", "Selling Plans Ski Wax",
                "id", "commerce://resource/ProductVariant/44506675314771"
            )))))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(cartHandler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(cartMeta));
        when(registry.findHandler("commerce_search_catalog")).thenReturn(Optional.of(catalogHandler));
        when(registry.findMetadata("commerce_search_catalog")).thenReturn(Optional.of(catalogMeta));

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of(
                    "product_search_query", "Selling Plans Ski Wax",
                    "quantity", 1
                ))
            ))
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-storefront-target-catalog-resolved-cart")
            .build();
        PipelineContext context = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .orchestrationPolicy(cartReadPolicy())
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("att#1")
                .contentText("Current page: cart. Page title: Shopping Companion Test")
                .metadata(Map.of("pageType", "cart"))
                .build()))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = newStep(registry).process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> catalogParamsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(catalogHandler, times(1)).executeAction(catalogParamsCaptor.capture(), any());
        assertThat(catalogParamsCaptor.getValue()).containsEntry("query", "Selling Plans Ski Wax");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> cartParamsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cartHandler, times(1)).getConfirmationMessage(cartParamsCaptor.capture(), any());
        Object raw = cartParamsCaptor.getValue().get("add_items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/44506675314771")
            .containsEntry("quantity", 1L));
        verify(cartHandler, never()).executeAction(anyMap(), any());
    }

    @Test
    void shouldExecuteResolvedCartAddItemAfterConfirmationWithoutLosingTrust() {
        AIActionMetaData cartMeta = commerceCartMeta();
        AIActionMetaData catalogMeta = commerceSearchCatalogMeta();
        AIActionHandler cartHandler = mock(AIActionHandler.class);
        AIActionHandler catalogHandler = mock(AIActionHandler.class);
        when(cartHandler.validateActionAllowed(any())).thenReturn(true);
        when(cartHandler.requiresConfirmation()).thenReturn(true);
        when(cartHandler.actionRuntimeConfig()).thenReturn(commerceCartRuntimeConfig());
        when(cartHandler.getConfirmationMessage(anyMap(), any())).thenReturn("Add 1 Selling Plans Ski Wax to your cart?");
        when(cartHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("Cart updated.")
            .data(ActionResultContracts.object(Map.of("status", "updated")))
            .build());
        when(catalogHandler.validateActionAllowed(any())).thenReturn(true);
        when(catalogHandler.requiresConfirmation()).thenReturn(false);
        when(catalogHandler.executeAction(anyMap(), any())).thenReturn(ActionResult.builder()
            .success(true)
            .message("catalog search")
            .data(ActionResultContracts.object(Map.of("documents", List.of(Map.of(
                "title", "Selling Plans Ski Wax",
                "id", "commerce://resource/ProductVariant/44506675314771"
            )))))
            .build());

        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findHandler("commerce_update_cart")).thenReturn(Optional.of(cartHandler));
        when(registry.findMetadata("commerce_update_cart")).thenReturn(Optional.of(cartMeta));
        when(registry.findHandler("commerce_search_catalog")).thenReturn(Optional.of(catalogHandler));
        when(registry.findMetadata("commerce_search_catalog")).thenReturn(Optional.of(catalogMeta));

        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        IntentHandlingStep step = newStep(registry, pendingActionStore);

        Intent actionIntent = Intent.builder()
            .type(IntentType.ACTION)
            .action("commerce_update_cart")
            .actionParams(Map.of(
                "add_items", List.of(Map.of(
                    "product_search_query", "Selling Plans Ski Wax",
                    "quantity", 1
                ))
            ))
            .build();
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .conversationId("chat-resolved-catalog-confirm")
            .build();
        PipelineContext turn1 = PipelineContext.from("Add Selling Plans Ski Wax to my cart.", orchestrationContext)
            .toBuilder()
            .orchestrationPolicy(cartReadPolicy())
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("att#1")
                .contentText("Current page: cart. Page title: Shopping Companion Test")
                .metadata(Map.of("pageType", "cart"))
                .build()))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(actionIntent)).build())
            .build();

        OrchestrationResult turn1Result = step.process(turn1).getIntentResult();

        assertThat(turn1Result.getType()).isEqualTo(OrchestrationResultType.CONFIRMATION_REQUIRED);
        assertThat(pendingActionStore.peekPendingAction("chat-resolved-catalog-confirm", "user"))
            .get()
            .satisfies(pending -> assertThat(pending.trustedEvidenceValuesByKey())
                .containsEntry("id", List.of("commerce://resource/productvariant/44506675314771")));

        Intent confirmIntent = Intent.builder()
            .type(IntentType.CONFIRMATION_POSITIVE)
            .build();
        PipelineContext turn2 = PipelineContext.from("Yes, confirm", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(confirmIntent)).build())
            .build();

        OrchestrationResult turn2Result = step.process(turn2).getIntentResult();

        assertThat(turn2Result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(turn2Result.isSuccess()).isTrue();
        assertThat(pendingActionStore.peekPendingAction("chat-resolved-catalog-confirm", "user")).isEmpty();
        verify(catalogHandler, times(1)).executeAction(anyMap(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(cartHandler, times(1)).executeAction(paramsCaptor.capture(), any());
        Object raw = paramsCaptor.getValue().get("add_items");
        assertThat(raw).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) raw;
        assertThat(items).singleElement().satisfies(item -> assertThat(item)
            .containsEntry("product_variant_id", "commerce://resource/ProductVariant/44506675314771")
            .containsEntry("quantity", 1L));
    }

    private IntentHandlingStep newStep(AIActionRegistry registry) {
        return newStep(registry, new InMemoryPendingActionStore());
    }

    private IntentHandlingStep newStep(AIActionRegistry registry, InMemoryPendingActionStore pendingActionStore) {
        return new IntentHandlingStep(
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
    }

    private AIActionMetaData addToCartMeta() {
        AIActionParamSchema sku = AIActionParamSchema.builder()
            .name("sku")
            .type(AIActionParamType.STRING)
            .build();
        AIActionParamSchema quantity = AIActionParamSchema.builder()
            .name("quantity")
            .type(AIActionParamType.INTEGER)
            .defaultValue(1)
            .build();
        AIActionParamSchema item = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of("sku", sku, "quantity", quantity))
            .requiredProperties(List.of("sku", "quantity"))
            .build();
        AIActionParamSchema items = AIActionParamSchema.builder()
            .name("items")
            .type(AIActionParamType.ARRAY)
            .batchTargets(true)
            .items(item)
            .build();

        return AIActionMetaData.builder()
            .name("add_to_cart")
            .description("Add to cart")
            .category("commerce")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .parameterSchemas(Map.of("items", items))
            .requiredParameters(Set.of("items"))
            .build();
    }

    private AIActionMetaData commerceCartMeta() {
        AIActionParamSchema variantId = AIActionParamSchema.builder()
            .name("product_variant_id")
            .type(AIActionParamType.STRING)
            .pattern("^commerce://resource/ProductVariant/[0-9]+$")
            .evidenceBound(true)
            .evidenceKeys(List.of("product_variant_id", "firstAvailableVariantId", "id"))
            .evidenceFallbackPolicy("CLARIFY")
            .build();
        AIActionParamSchema quantity = AIActionParamSchema.builder()
            .name("quantity")
            .type(AIActionParamType.INTEGER)
            .min(1L)
            .defaultValue(1)
            .build();
        AIActionParamSchema productSearchQuery = AIActionParamSchema.builder()
            .name("product_search_query")
            .description("Resolver-only product search phrase for cart mutations")
            .type(AIActionParamType.STRING)
            .build();
        AIActionParamSchema item = AIActionParamSchema.builder()
            .type(AIActionParamType.OBJECT)
            .properties(Map.of("product_variant_id", variantId, "quantity", quantity))
            .requiredProperties(List.of("product_variant_id", "quantity"))
            .build();
        AIActionParamSchema addItems = AIActionParamSchema.builder()
            .name("add_items")
            .type(AIActionParamType.ARRAY)
            .batchTargets(true)
            .resolveFrom(Map.of(
                "source", "READ_ACTION",
                "actionName", "commerce_search_catalog",
                "params", Map.of(
                    "query", "{{params.product_search_query|params.add_items.0.product_search_query|context.originalQuery|params.add_items.0.product_variant_id}}",
                    "limit", 1
                ),
                "resultPaths", List.of("documents.0", "results.0", "_items.0")
            ))
            .items(item)
            .build();

        return AIActionMetaData.builder()
            .name("commerce_update_cart")
            .description("Update commerce cart")
            .category("commerce")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .parameterSchemas(Map.of("product_search_query", productSearchQuery, "add_items", addItems))
            .build();
    }

    private AIActionMetaData commerceSearchCatalogMeta() {
        AIActionParamSchema query = AIActionParamSchema.builder()
            .name("query")
            .type(AIActionParamType.STRING)
            .required(true)
            .build();
        AIActionParamSchema limit = AIActionParamSchema.builder()
            .name("limit")
            .type(AIActionParamType.INTEGER)
            .min(1L)
            .build();
        return AIActionMetaData.builder()
            .name("commerce_search_catalog")
            .description("Search commerce catalog")
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .parameterSchemas(Map.of("query", query, "limit", limit))
            .requiredParameters(Set.of("query"))
            .build();
    }

    private AIActionMetaData commerceGetCartMeta() {
        AIActionParamSchema cartId = AIActionParamSchema.builder()
            .name("cart_id")
            .type(AIActionParamType.STRING)
            .required(true)
            .visibility("INTERNAL")
            .askUser(false)
            .resolveFrom(Map.of(
                "source", "OWNED_RESOURCE",
                "resourceType", "commerce.cart",
                "scope", "current_session",
                "handleField", "cart_id",
                "metadataKeys", List.of("cart_id", "cartId")
            ))
            .build();
        AIActionParamSchema shopperSessionId = AIActionParamSchema.builder()
            .name("shopperSessionId")
            .type(AIActionParamType.STRING)
            .required(true)
            .visibility("INTERNAL")
            .askUser(false)
            .resolveFrom(Map.of(
                "source", "RUNTIME_CONTEXT",
                "field", "sessionId"
            ))
            .build();

        return AIActionMetaData.builder()
            .name("commerce_get_cart")
            .description("Read current commerce cart")
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .anonymousAllowed(true)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .parameterSchemas(Map.of(
                "cart_id", cartId,
                "shopperSessionId", shopperSessionId
            ))
            .requiredParameters(Set.of("cart_id", "shopperSessionId"))
            .build();
    }

    private AIActionMetaData requestReturnMeta() {
        AIActionParamSchema orderNumber = AIActionParamSchema.builder()
            .name("order_number")
            .type(AIActionParamType.STRING)
            .required(true)
            .pattern("^(?=.*[0-9])[#A-Za-z0-9_-]+$")
            .resolveFrom(Map.of(
                "source", "READ_ACTION",
                "actionName", "commerce_get_most_recent_order_status",
                "resultPaths", List.of("order_number", "orderNumber", "order.number", "order.name")
            ))
            .build();
        AIActionParamSchema reason = AIActionParamSchema.builder()
            .name("reason")
            .type(AIActionParamType.STRING)
            .build();

        return AIActionMetaData.builder()
            .name("commerce_request_return")
            .description("Request return for authenticated shopper order")
            .category("commerce")
            .accessMode(ActionAccessMode.WRITE_ONLY)
            .parameterSchemas(Map.of("order_number", orderNumber, "reason", reason))
            .requiredParameters(Set.of("order_number"))
            .build();
    }

    private AIActionMetaData mostRecentOrderMeta() {
        return AIActionMetaData.builder()
            .name("commerce_get_most_recent_order_status")
            .description("Read authenticated shopper most recent order status")
            .category("commerce")
            .accessMode(ActionAccessMode.READ)
            .groundingEligible(true)
            .readActionResolutionEligible(true)
            .build();
    }

    private OrchestrationPolicy customerReadPolicy() {
        return new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "executor",
            "account",
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                List.of("commerce_get_most_recent_order_status"),
                true,
                1,
                2,
                2,
                1,
                4_000,
                2_400,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }

    private OrchestrationPolicy cartReadPolicy() {
        return new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "executor",
            "cart",
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.ReadActionResolutionPolicy(
                true,
                OrchestrationProperties.ReadActionResolutionPlanningMode.SINGLE_PASS,
                List.of("commerce_search_catalog", "commerce_get_cart"),
                true,
                1,
                2,
                2,
                1,
                4_000,
                2_400,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
                true
            ),
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }

    private Map<String, Object> commerceCartRuntimeConfig() {
        return Map.of(
            "adapterType", "mcp-tool",
            "requiresConfirmation", true,
            "execution", Map.of(
                "adapterType", "mcp-tool",
                "mcp", Map.of(
                    "serverRef", "commerce-storefront",
                    "toolName", "update_cart",
                    "requiredAnyArguments", List.of("add_items", "update_items", "remove_line_ids"),
                    "argumentTemplate", Map.of(
                        "add_items", "{{params.add_items}}",
                        "update_items", "{{params.update_items}}",
                        "remove_line_ids", "{{params.remove_line_ids}}"
                    )
                )
            )
        );
    }

    private ResolvedTarget target(String id, Map<String, String> metadata) {
        return ResolvedTarget.builder()
            .id(id)
            .vectorSpace("product")
            .metadata(metadata)
            .build();
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

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
            public java.util.stream.Stream<T> stream() {
                return value == null ? java.util.stream.Stream.empty() : java.util.stream.Stream.of(value);
            }
        };
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new com.ai.infrastructure.config.PromptBundleProperties()
        );
    }
}
