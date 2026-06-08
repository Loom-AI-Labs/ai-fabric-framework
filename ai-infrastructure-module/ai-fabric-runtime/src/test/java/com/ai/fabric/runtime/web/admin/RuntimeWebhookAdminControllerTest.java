package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.auth.RuntimeAuthCallerType;
import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.auth.RuntimeResolvedIdentity;
import com.ai.fabric.runtime.webhook.ActionWebhookDeliveryEntity;
import com.ai.fabric.runtime.webhook.ActionWebhookDeliveryRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RuntimeWebhookAdminControllerTest {

    @Test
    void overviewExposesRecentWebhookDeliveries() {
        ActionWebhookDeliveryRepository repository = mock(ActionWebhookDeliveryRepository.class);
        RuntimeRequestAuthResolver authResolver = mock(RuntimeRequestAuthResolver.class);

        RuntimeResolvedIdentity identity = RuntimeResolvedIdentity.builder()
            .authContext(RuntimeAuthContext.builder()
                .authMode(RuntimeAuthMode.PRIVATE_RUNTIME_BACKEND_MEDIATED)
                .callerType(RuntimeAuthCallerType.TRUSTED_BACKEND)
                .subjectType(RuntimeAuthSubjectType.INTERNAL_PLATFORM_USER)
                .subjectId("operator-1")
                .sessionId("session-1")
                .grantedScopes(List.of(RuntimeAdminScopeCatalog.RUNTIME_WEBHOOKS_OVERVIEW))
                .build())
            .warnings(List.of())
            .build();

        when(authResolver.resolveVerifiedPrivateContext(any(), eq("/api/admin/webhooks/overview"))).thenReturn(identity);
        doNothing().when(authResolver)
            .requireScope(eq(identity), eq(RuntimeAdminScopeCatalog.RUNTIME_WEBHOOKS_OVERVIEW), eq("/api/admin/webhooks/overview"));

        ActionWebhookDeliveryEntity delivery = new ActionWebhookDeliveryEntity();
        delivery.setId("whd-1");
        delivery.setActionName("cancel_order");
        delivery.setEventType("order.cancelled");
        delivery.setTargetRef("zapier_orders");
        delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_FAILED);
        delivery.setAttemptCount(2);
        delivery.setMaxAttempts(5);
        delivery.setLastStatusCode(500);
        delivery.setLastError("Server error");
        delivery.setCreatedAt(Instant.parse("2026-04-17T10:00:00Z"));
        delivery.setUpdatedAt(Instant.parse("2026-04-17T10:05:00Z"));
        delivery.setNextAttemptAt(Instant.parse("2026-04-17T10:06:00Z"));

        when(repository.findAllByOrderByCreatedAtDesc(any())).thenReturn(List.of(delivery));
        when(repository.countByStatus(ActionWebhookDeliveryEntity.STATUS_PENDING)).thenReturn(1L);
        when(repository.countByStatus(ActionWebhookDeliveryEntity.STATUS_RETRY_PENDING)).thenReturn(0L);
        when(repository.countByStatus(ActionWebhookDeliveryEntity.STATUS_IN_PROGRESS)).thenReturn(0L);
        when(repository.countByStatus(ActionWebhookDeliveryEntity.STATUS_DELIVERED)).thenReturn(3L);
        when(repository.countByStatus(ActionWebhookDeliveryEntity.STATUS_FAILED)).thenReturn(1L);

        RuntimeWebhookAdminController controller = new RuntimeWebhookAdminController(
            repository,
            authResolver,
            Clock.fixed(Instant.parse("2026-04-17T10:10:00Z"), ZoneOffset.UTC)
        );

        ResponseEntity<?> response = controller.overview(50, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("contractVersion", "RUNTIME_WEBHOOK_DELIVERY_OVERVIEW_V1");
        assertThat(body).containsEntry("retrySupported", true);
        assertThat(body).containsEntry("retryEligibleCount", 1L);
        assertThat(body.get("counts")).isEqualTo(Map.of(
            "PENDING", 1L,
            "RETRY_PENDING", 0L,
            "IN_PROGRESS", 0L,
            "DELIVERED", 3L,
            "FAILED", 1L
        ));
        assertThat(body.get("recentDeliveries")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recentDeliveries = (List<Map<String, Object>>) body.get("recentDeliveries");
        assertThat(recentDeliveries).hasSize(1);
        assertThat(recentDeliveries.get(0)).containsEntry("id", "whd-1");
        assertThat(recentDeliveries.get(0)).containsEntry("actionName", "cancel_order");
        assertThat(recentDeliveries.get(0)).containsEntry("eventType", "order.cancelled");
        assertThat(recentDeliveries.get(0)).containsEntry("targetRef", "zapier_orders");
        assertThat(recentDeliveries.get(0)).containsEntry("status", "FAILED");
        assertThat(recentDeliveries.get(0)).containsEntry("attemptCount", 2);
        assertThat(recentDeliveries.get(0)).containsEntry("maxAttempts", 5);
        assertThat(recentDeliveries.get(0)).containsEntry("lastStatusCode", 500);
        assertThat(recentDeliveries.get(0)).containsEntry("lastError", "Server error");
        assertThat(recentDeliveries.get(0)).containsEntry("deploymentId", null);
        assertThat(recentDeliveries.get(0)).containsEntry("conversationId", null);
        assertThat(recentDeliveries.get(0)).containsEntry("requestId", null);
        assertThat(recentDeliveries.get(0)).containsEntry("nextAttemptAt", Instant.parse("2026-04-17T10:06:00Z"));
        assertThat(recentDeliveries.get(0)).containsEntry("claimedAt", null);
        assertThat(recentDeliveries.get(0)).containsEntry("deliveredAt", null);
        assertThat(recentDeliveries.get(0)).containsEntry("createdAt", Instant.parse("2026-04-17T10:00:00Z"));
        assertThat(recentDeliveries.get(0)).containsEntry("updatedAt", Instant.parse("2026-04-17T10:05:00Z"));
        assertThat(recentDeliveries.get(0)).containsEntry("retryEligible", true);
    }

    @Test
    void retryQueuesFailedDelivery() {
        ActionWebhookDeliveryRepository repository = mock(ActionWebhookDeliveryRepository.class);
        RuntimeRequestAuthResolver authResolver = mock(RuntimeRequestAuthResolver.class);

        RuntimeResolvedIdentity identity = RuntimeResolvedIdentity.builder()
            .authContext(RuntimeAuthContext.builder()
                .authMode(RuntimeAuthMode.PRIVATE_RUNTIME_BACKEND_MEDIATED)
                .callerType(RuntimeAuthCallerType.TRUSTED_BACKEND)
                .subjectType(RuntimeAuthSubjectType.INTERNAL_PLATFORM_USER)
                .subjectId("operator-1")
                .sessionId("session-1")
                .grantedScopes(List.of(RuntimeAdminScopeCatalog.RUNTIME_WEBHOOKS_RETRY))
                .build())
            .warnings(List.of())
            .build();

        when(authResolver.resolveVerifiedPrivateContext(any(), eq("/api/admin/webhooks/deliveries/{deliveryId}/retry"))).thenReturn(identity);
        doNothing().when(authResolver)
            .requireScope(eq(identity), eq(RuntimeAdminScopeCatalog.RUNTIME_WEBHOOKS_RETRY), eq("/api/admin/webhooks/deliveries/{deliveryId}/retry"));

        ActionWebhookDeliveryEntity delivery = new ActionWebhookDeliveryEntity();
        delivery.setId("whd-2");
        delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_FAILED);
        delivery.setNextAttemptAt(Instant.parse("2026-04-17T10:00:00Z"));

        when(repository.findById("whd-2")).thenReturn(Optional.of(delivery));

        RuntimeWebhookAdminController controller = new RuntimeWebhookAdminController(
            repository,
            authResolver,
            Clock.fixed(Instant.parse("2026-04-17T10:10:00Z"), ZoneOffset.UTC)
        );

        ResponseEntity<?> response = controller.retry("whd-2", new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(delivery.getStatus()).isEqualTo(ActionWebhookDeliveryEntity.STATUS_RETRY_PENDING);
        assertThat(delivery.getNextAttemptAt()).isEqualTo(Instant.parse("2026-04-17T10:10:00Z"));
        verify(repository).findById("whd-2");
    }
}
