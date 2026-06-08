package com.ai.fabric.runtime.webhook;

import com.ai.infrastructure.http.OutboundHttpExecutionRequest;
import com.ai.infrastructure.http.OutboundHttpExecutionResponse;
import com.ai.infrastructure.http.OutboundHttpExecutor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeActionWebhookDeliveryWorkerTest {

    @Test
    void dispatchReadyDeliveriesMarksSuccessfulResponsesDelivered() {
        RuntimeWebhookSecretResolver secretResolver = mock(RuntimeWebhookSecretResolver.class);
        RuntimeActionWebhookDeliveryStore deliveryStore = mock(RuntimeActionWebhookDeliveryStore.class);
        when(secretResolver.resolveRequired("ZAPIER_URL")).thenReturn("https://hooks.example.test/zapier");
        when(deliveryStore.claimReadyDeliveries()).thenReturn(List.of(delivery("whd-1")));
        RuntimeActionWebhookDeliveryWorker worker = new RuntimeActionWebhookDeliveryWorker(
            deliveryStore,
            new StubOutboundExecutor(new OutboundHttpExecutionResponse(202, "", java.util.Map.of())),
            secretResolver,
            Clock.fixed(Instant.parse("2026-04-17T10:00:00Z"), ZoneOffset.UTC)
        );

        worker.dispatchReadyDeliveries();

        verify(deliveryStore).markDelivered("whd-1", 202);
    }

    @Test
    void dispatchReadyDeliveriesMarksClientErrorsFailed() {
        RuntimeWebhookSecretResolver secretResolver = mock(RuntimeWebhookSecretResolver.class);
        RuntimeActionWebhookDeliveryStore deliveryStore = mock(RuntimeActionWebhookDeliveryStore.class);
        when(secretResolver.resolveRequired("ZAPIER_URL")).thenReturn("https://hooks.example.test/zapier");
        when(deliveryStore.claimReadyDeliveries()).thenReturn(List.of(delivery("whd-2")));
        RuntimeActionWebhookDeliveryWorker worker = new RuntimeActionWebhookDeliveryWorker(
            deliveryStore,
            new StubOutboundExecutor(new OutboundHttpExecutionResponse(400, "", java.util.Map.of())),
            secretResolver,
            Clock.fixed(Instant.parse("2026-04-17T10:00:00Z"), ZoneOffset.UTC)
        );

        worker.dispatchReadyDeliveries();

        verify(deliveryStore).markFailed("whd-2", 400, "Webhook delivery returned non-retryable HTTP 400.");
    }

    @Test
    void dispatchReadyDeliveriesRejectsWebhookUrlsWithUserInfo() {
        RuntimeWebhookSecretResolver secretResolver = mock(RuntimeWebhookSecretResolver.class);
        RuntimeActionWebhookDeliveryStore deliveryStore = mock(RuntimeActionWebhookDeliveryStore.class);
        when(secretResolver.resolveRequired("ZAPIER_URL")).thenReturn("https://user:pass@hooks.example.test/zapier");
        when(deliveryStore.claimReadyDeliveries()).thenReturn(List.of(delivery("whd-3")));
        RuntimeActionWebhookDeliveryWorker worker = new RuntimeActionWebhookDeliveryWorker(
            deliveryStore,
            new StubOutboundExecutor(new OutboundHttpExecutionResponse(202, "", java.util.Map.of())),
            secretResolver,
            Clock.fixed(Instant.parse("2026-04-17T10:00:00Z"), ZoneOffset.UTC)
        );

        worker.dispatchReadyDeliveries();

        verify(deliveryStore).markFailed("whd-3", null, "Webhook target URL must not include user info.");
    }

    private static ActionWebhookDeliveryEntity delivery(String id) {
        ActionWebhookDeliveryEntity entity = new ActionWebhookDeliveryEntity();
        entity.setId(id);
        entity.setActionName("cancel_order");
        entity.setEventType("order.cancelled");
        entity.setTargetRef("zapier_orders");
        entity.setUrlSecretRef("ZAPIER_URL");
        entity.setTimeoutMs(3000);
        entity.setMaxAttempts(5);
        entity.setAttemptCount(0);
        entity.setStatus(ActionWebhookDeliveryEntity.STATUS_PENDING);
        entity.setPayloadJson("{\"eventType\":\"order.cancelled\"}");
        entity.setNextAttemptAt(Instant.parse("2026-04-17T10:00:00Z"));
        return entity;
    }

    private record StubOutboundExecutor(OutboundHttpExecutionResponse response) implements OutboundHttpExecutor {
        @Override
        public OutboundHttpExecutionResponse execute(OutboundHttpExecutionRequest request) {
            return response;
        }
    }
}
