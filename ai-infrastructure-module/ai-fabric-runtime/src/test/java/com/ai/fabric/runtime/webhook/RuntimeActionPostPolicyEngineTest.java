package com.ai.fabric.runtime.webhook;

import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionPayload;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.connector.ConnectorActionPostPolicyDefinition;
import com.ai.infrastructure.intent.action.connector.ConnectorActionWebhookPolicyCatalog;
import com.ai.infrastructure.intent.action.connector.ConnectorWebhookTargetDefinition;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationContextMetadataKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeActionPostPolicyEngineTest {

    @Test
    void handleSuccessfulActionEnqueuesWebhookDeliveries() throws Exception {
        ConnectorActionWebhookPolicyCatalog webhookPolicyCatalog = mock(ConnectorActionWebhookPolicyCatalog.class);
        ActionWebhookDeliveryRepository repository = mock(ActionWebhookDeliveryRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-04-17T10:00:00Z"), ZoneOffset.UTC);
        RuntimeActionPostPolicyEngine engine = new RuntimeActionPostPolicyEngine(
            webhookPolicyCatalog,
            repository,
            new ObjectMapper(),
            clock
        );

        when(webhookPolicyCatalog.postPoliciesForAction("cancel_order")).thenReturn(java.util.List.of(
            new ConnectorActionPostPolicyDefinition("webhook", "zapier_orders", "order.cancelled")
        ));
        when(webhookPolicyCatalog.webhookTargetsById()).thenReturn(Map.of(
            "zapier_orders",
            new ConnectorWebhookTargetDefinition("zapier_orders", "ZAPIER_URL", "ZAPIER_SIGNING_SECRET", 3000, 5)
        ));

        engine.handleSuccessfulAction(
            "cancel_order",
            Map.of("orderId", "O-100"),
            ActionResult.builder()
                .success(true)
                .message("Cancelled")
                .data(ActionPayload.object(Map.of("status", "cancelled")))
                .build(),
            new ActionContext(
                OrchestrationContext.builder()
                    .requestId("req-1")
                    .conversationId("conv-1")
                    .sessionId("session-1")
                    .metadata(Map.of(
                        OrchestrationContextMetadataKeys.SUBJECT_ID, "user-1",
                        OrchestrationContextMetadataKeys.SUBJECT_TYPE, "END_USER",
                        OrchestrationContextMetadataKeys.DEPLOYMENT_ID, "dep-1",
                        OrchestrationContextMetadataKeys.CUSTOMER_ID, "cust-1",
                        OrchestrationContextMetadataKeys.TENANT_ID, "tenant-1",
                        OrchestrationContextMetadataKeys.AUTH_MODE, "PLATFORM_PROXY_SESSION"
                    ))
                    .build(),
                null
            )
        );

        ArgumentCaptor<ActionWebhookDeliveryEntity> entityCaptor = ArgumentCaptor.forClass(ActionWebhookDeliveryEntity.class);
        verify(repository).save(entityCaptor.capture());
        ActionWebhookDeliveryEntity entity = entityCaptor.getValue();
        assertThat(entity.getActionName()).isEqualTo("cancel_order");
        assertThat(entity.getEventType()).isEqualTo("order.cancelled");
        assertThat(entity.getTargetRef()).isEqualTo("zapier_orders");
        assertThat(entity.getUrlSecretRef()).isEqualTo("ZAPIER_URL");
        assertThat(entity.getSigningSecretRef()).isEqualTo("ZAPIER_SIGNING_SECRET");
        assertThat(entity.getStatus()).isEqualTo(ActionWebhookDeliveryEntity.STATUS_PENDING);
        assertThat(entity.getAttemptCount()).isEqualTo(0);
        assertThat(entity.getNextAttemptAt()).isEqualTo(Instant.parse("2026-04-17T10:00:00Z"));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = new ObjectMapper().readValue(entity.getPayloadJson(), Map.class);
        assertThat(payload.get("eventType")).isEqualTo("order.cancelled");
        assertThat(payload.get("deploymentId")).isEqualTo("dep-1");
        @SuppressWarnings("unchecked")
        Map<String, Object> actionPayload = (Map<String, Object>) payload.get("action");
        assertThat(actionPayload.get("name")).isEqualTo("cancel_order");
    }
}
