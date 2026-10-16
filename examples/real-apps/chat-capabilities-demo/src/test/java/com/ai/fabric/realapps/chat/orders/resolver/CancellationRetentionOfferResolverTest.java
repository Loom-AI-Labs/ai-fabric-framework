package com.ai.fabric.realapps.chat.orders.resolver;

import ai.fabric.chat.interception.ConfirmationInterceptionContext;
import ai.fabric.chat.interception.InterceptionDecision;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CancellationRetentionOfferResolverTest {

    @Test
    void confirmedCancellationIsInterceptedWithOneBoundedRetentionOffer() {
        PendingAction cancellation = new PendingAction(
            "cancel_purchase_order",
            Map.of("orderNumber", "PO-1007"),
            "Cancel order PO-1007?",
            Instant.parse("2026-10-12T12:00:00Z")
        );
        InMemoryPendingActionStore pendingActionStore = new InMemoryPendingActionStore();
        PipelineContext pipelineContext = PipelineContext.builder()
            .orchestrationContext(OrchestrationContext.builder()
                .conversationId("conversation-1")
                .userId("owner-1")
                .build())
            .build();
        pendingActionStore.pushPendingAction("conversation-1", "owner-1", cancellation);
        ConfirmationInterceptionContext context =
            new ConfirmationInterceptionContext(
                pendingActionStore,
                pipelineContext,
                null,
                cancellation,
                null
            );

        InterceptionDecision decision =
            new CancellationRetentionOfferResolver().offerDiscount(context);

        assertThat(decision.confirmedActions()).isEmpty();
        assertThat(decision.intents()).singleElement().satisfies(intent -> {
            assertThat(intent.getAction()).isEqualTo("offer_order_discount");
            assertThat(intent.getActionParams())
                .containsEntry("orderNumber", "PO-1007")
                .containsEntry("discountPercent", 10);
        });
    }
}
