package com.ai.infrastructure.chat.resolver;

import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.action.PendingAction;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecision;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorRule;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorTrigger;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThat;

class ConfiguredConfirmationInterceptorsResolverTest {

    @Test
    void shouldPromptRetentionOfferAndSetOnceParam() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        ConfiguredConfirmationInterceptorsResolver resolver = new ConfiguredConfirmationInterceptorsResolver(
            store,
            provider(retentionRules())
        );

        PipelineContext context = context("conv-1");
        store.pushPendingAction("conv-1", "demo-user", new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-1"),
            null,
            Instant.now()
        ));

        PipelineContext updated = resolver.resolve(confirmation(IntentType.CONFIRMATION_POSITIVE), Map.of(), context);

        Intent updatedIntent = updated.getIntentResponse().getIntents().getFirst();
        assertThat(updatedIntent.getType()).isEqualTo(IntentType.ACTION);
        assertThat(updatedIntent.getAction()).isEqualTo("offer_order_discount");
        assertThat(updatedIntent.getActionParams())
            .containsEntry("orderNumber", "PO-1")
            .containsEntry("discountPercent", 10);

        PendingAction top = store.peekPendingAction("conv-1", "demo-user").orElseThrow();
        assertThat(top.action()).isEqualTo("cancel_purchase_order");
        assertThat(top.actionParams()).containsEntry("_retentionofferoffered", true);
        assertThat(resolver.canResolve(confirmation(IntentType.CONFIRMATION_POSITIVE), Map.of(), context)).isFalse();
    }

    @Test
    void shouldExecuteRetentionOfferAndClearOriginalCancellation() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        ConfiguredConfirmationInterceptorsResolver resolver = new ConfiguredConfirmationInterceptorsResolver(
            store,
            provider(retentionRules())
        );

        PipelineContext context = context("conv-2");
        store.pushPendingAction("conv-2", "demo-user", new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-1"),
            null,
            Instant.now()
        ));
        store.pushPendingAction("conv-2", "demo-user", new PendingAction(
            "offer_order_discount",
            Map.of("orderNumber", "PO-1", "discountPercent", 15),
            null,
            Instant.now()
        ));

        PipelineContext updated = resolver.resolve(confirmation(IntentType.CONFIRMATION_POSITIVE), Map.of(), context);

        Intent updatedIntent = updated.getIntentResponse().getIntents().getFirst();
        assertThat(updatedIntent.getAction()).isEqualTo("offer_order_discount");
        assertThat(updatedIntent.getActionParams())
            .containsEntry("orderNumber", "PO-1")
            .containsEntry("discountPercent", 15);
        assertThat(updated.getConfirmedActions()).containsExactly("offer_order_discount");
        assertThat(store.getPendingActionStack("conv-2", "demo-user")).isEmpty();
    }

    @Test
    void shouldExecuteOriginalCancellationWhenRetentionOfferRejected() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        ConfiguredConfirmationInterceptorsResolver resolver = new ConfiguredConfirmationInterceptorsResolver(
            store,
            provider(retentionRules())
        );

        PipelineContext context = context("conv-3");
        store.pushPendingAction("conv-3", "demo-user", new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-9", "orderId", 42),
            null,
            Instant.now()
        ));
        store.pushPendingAction("conv-3", "demo-user", new PendingAction(
            "offer_order_discount",
            Map.of("orderNumber", "PO-9", "discountPercent", 10),
            null,
            Instant.now()
        ));

        PipelineContext updated = resolver.resolve(confirmation(IntentType.CONFIRMATION_NEGATIVE), Map.of(), context);

        Intent updatedIntent = updated.getIntentResponse().getIntents().getFirst();
        assertThat(updatedIntent.getAction()).isEqualTo("cancel_purchase_order");
        assertThat(updatedIntent.getActionParams())
            .containsEntry("orderNumber", "PO-9")
            .containsEntry("orderId", 42);
        assertThat(updated.getConfirmedActions()).containsExactly("cancel_purchase_order");
        assertThat(store.getPendingActionStack("conv-3", "demo-user")).isEmpty();
    }

    @Test
    void shouldPreserveNonConfirmationIntentsWhenConfiguredInterceptorMatchesCompoundTurn() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        ConfiguredConfirmationInterceptorsResolver resolver = new ConfiguredConfirmationInterceptorsResolver(
            store,
            provider(retentionRules())
        );

        PipelineContext context = context("conv-4");
        store.pushPendingAction("conv-4", "demo-user", new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-7"),
            null,
            Instant.now()
        ));

        MultiIntentResponse compound = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build(),
                Intent.builder().type(IntentType.ACTION).action("show_orders").confidence(0.9d).build()
            ))
            .build();

        PipelineContext updated = resolver.resolve(compound, Map.of(), context);

        assertThat(updated.getIntentResponse().getIntents())
            .extracting(Intent::getAction)
            .containsExactly("offer_order_discount", "show_orders");
    }

    @Test
    void shouldSuppressCorrelatedActionIntentsWhenPreservingCompoundTurnFollowUps() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        ConfiguredConfirmationInterceptorsResolver resolver = new ConfiguredConfirmationInterceptorsResolver(
            store,
            provider(retentionRules())
        );

        PipelineContext context = context("conv-6");
        store.pushPendingAction("conv-6", "demo-user", new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-8"),
            null,
            Instant.now()
        ));

        MultiIntentResponse compound = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).build(),
                Intent.builder().type(IntentType.ACTION).action("cancel_purchase_order").confidence(0.9d).build(),
                Intent.builder().type(IntentType.ACTION).action("offer_order_discount").confidence(0.9d).build(),
                Intent.builder().type(IntentType.ACTION).action("show_orders").confidence(0.9d).build()
            ))
            .build();

        PipelineContext updated = resolver.resolve(compound, Map.of(), context);

        assertThat(updated.getIntentResponse().getIntents())
            .extracting(Intent::getAction)
            .containsExactly("offer_order_discount", "show_orders");
    }

    @Test
    void shouldHonorOnceParamRegardlessOfStoredKeyCase() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        ConfiguredConfirmationInterceptorsResolver resolver = new ConfiguredConfirmationInterceptorsResolver(
            store,
            provider(retentionRulesWithMixedCaseOnceParam())
        );

        PipelineContext context = context("conv-5");
        store.pushPendingAction("conv-5", "demo-user", new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-5", "_retentionofferoffered", true),
            null,
            Instant.now()
        ));

        assertThat(resolver.canResolve(confirmation(IntentType.CONFIRMATION_POSITIVE), Map.of(), context)).isFalse();
    }

    private ObjectProvider<ConfirmationInterceptorCatalogProvider> provider(List<ConfirmationInterceptorRule> rules) {
        ConfirmationInterceptorCatalogProvider provider = new ConfirmationInterceptorCatalogProvider() {
            @Override
            public List<ConfirmationInterceptorRule> getRules() {
                return rules;
            }
        };
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("confirmationInterceptorCatalogProvider", provider);
        return beanFactory.getBeanProvider(ConfirmationInterceptorCatalogProvider.class);
    }

    private PipelineContext context(String conversationId) {
        return PipelineContext.from("yes", OrchestrationContext.builder()
            .userId("demo-user")
            .conversationId(conversationId)
            .build());
    }

    private MultiIntentResponse confirmation(IntentType type) {
        return MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(type).build()))
            .build();
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
            ),
            new ConfirmationInterceptorRule(
                "reject_retention_offer",
                new ConfirmationInterceptorTrigger(
                    List.of("offer_order_discount"),
                    IntentType.CONFIRMATION_NEGATIVE,
                    null
                ),
                new ConfirmationInterceptorDecision(
                    ConfirmationInterceptorDecisionType.EXECUTE_ACTION,
                    "cancel_purchase_order",
                    Map.of(
                        "orderNumber", "{{stack.previous.actionParams.orderNumber}}",
                        "orderId", "{{stack.previous.actionParams.orderId}}"
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
}
