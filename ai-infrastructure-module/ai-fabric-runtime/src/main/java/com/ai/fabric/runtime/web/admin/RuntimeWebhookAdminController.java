package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.webhook.ActionWebhookDeliveryEntity;
import com.ai.fabric.runtime.webhook.ActionWebhookDeliveryRepository;
import jakarta.transaction.Transactional;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/webhooks")
@RequiredArgsConstructor
public class RuntimeWebhookAdminController {

    private final ActionWebhookDeliveryRepository deliveryRepository;
    private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;
    private final Clock clock;

    @GetMapping("/overview")
    public ResponseEntity<?> overview(@RequestParam(defaultValue = "50") int limit,
                                      HttpServletRequest httpRequest) {
        authorize(httpRequest, RuntimeAdminScopeCatalog.RUNTIME_WEBHOOKS_OVERVIEW, "/api/admin/webhooks/overview");

        int clampedLimit = Math.max(1, Math.min(limit, 200));
        List<ActionWebhookDeliveryEntity> deliveries = deliveryRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, clampedLimit));

        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put(ActionWebhookDeliveryEntity.STATUS_PENDING, deliveryRepository.countByStatus(ActionWebhookDeliveryEntity.STATUS_PENDING));
        counts.put(ActionWebhookDeliveryEntity.STATUS_RETRY_PENDING, deliveryRepository.countByStatus(ActionWebhookDeliveryEntity.STATUS_RETRY_PENDING));
        counts.put(ActionWebhookDeliveryEntity.STATUS_IN_PROGRESS, deliveryRepository.countByStatus(ActionWebhookDeliveryEntity.STATUS_IN_PROGRESS));
        counts.put(ActionWebhookDeliveryEntity.STATUS_DELIVERED, deliveryRepository.countByStatus(ActionWebhookDeliveryEntity.STATUS_DELIVERED));
        counts.put(ActionWebhookDeliveryEntity.STATUS_FAILED, deliveryRepository.countByStatus(ActionWebhookDeliveryEntity.STATUS_FAILED));

        long retryEligibleCount = deliveries.stream()
            .filter(delivery -> ActionWebhookDeliveryEntity.STATUS_FAILED.equals(delivery.getStatus()))
            .count();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("contractVersion", "RUNTIME_WEBHOOK_DELIVERY_OVERVIEW_V1");
        body.put("retrySupported", true);
        body.put("counts", counts);
        body.put("recentDeliveries", deliveries.stream().map(this::toSummary).toList());
        body.put("retryEligibleCount", retryEligibleCount);
        body.put("limit", clampedLimit);
        return ResponseEntity.ok(body);
    }

    @PostMapping("/deliveries/{deliveryId}/retry")
    @Transactional
    public ResponseEntity<?> retry(@PathVariable String deliveryId,
                                   HttpServletRequest httpRequest) {
        authorize(httpRequest, RuntimeAdminScopeCatalog.RUNTIME_WEBHOOKS_RETRY, "/api/admin/webhooks/deliveries/{deliveryId}/retry");

        ActionWebhookDeliveryEntity delivery = deliveryRepository.findById(deliveryId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Webhook delivery not found: " + deliveryId));

        if (ActionWebhookDeliveryEntity.STATUS_IN_PROGRESS.equals(delivery.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Webhook delivery is already in progress.");
        }
        if (ActionWebhookDeliveryEntity.STATUS_DELIVERED.equals(delivery.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Delivered webhook events cannot be retried directly.");
        }

        Instant now = Instant.now(clock != null ? clock : Clock.systemUTC());
        delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_RETRY_PENDING);
        delivery.setClaimedAt(null);
        delivery.setNextAttemptAt(now);
        delivery.setUpdatedAt(now);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("deliveryId", delivery.getId());
        body.put("status", delivery.getStatus());
        body.put("nextAttemptAt", delivery.getNextAttemptAt());
        body.put("summaryMessage", "Webhook delivery queued for retry.");
        return ResponseEntity.ok(body);
    }

    private void authorize(HttpServletRequest request, String scope, String surface) {
        runtimeRequestAuthResolver.requireScope(
            runtimeRequestAuthResolver.resolveVerifiedPrivateContext(request, surface),
            scope,
            surface
        );
    }

    private Map<String, Object> toSummary(ActionWebhookDeliveryEntity delivery) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", delivery.getId());
        out.put("actionName", delivery.getActionName());
        out.put("eventType", delivery.getEventType());
        out.put("targetRef", delivery.getTargetRef());
        out.put("status", delivery.getStatus());
        out.put("attemptCount", delivery.getAttemptCount());
        out.put("maxAttempts", delivery.getMaxAttempts());
        out.put("lastStatusCode", delivery.getLastStatusCode());
        out.put("lastError", delivery.getLastError());
        out.put("deploymentId", delivery.getDeploymentId());
        out.put("conversationId", delivery.getConversationId());
        out.put("requestId", delivery.getRequestId());
        out.put("nextAttemptAt", delivery.getNextAttemptAt());
        out.put("claimedAt", delivery.getClaimedAt());
        out.put("deliveredAt", delivery.getDeliveredAt());
        out.put("createdAt", delivery.getCreatedAt());
        out.put("updatedAt", delivery.getUpdatedAt());
        out.put(
            "retryEligible",
            StringUtils.hasText(delivery.getId()) && ActionWebhookDeliveryEntity.STATUS_FAILED.equals(delivery.getStatus())
        );
        return out;
    }
}
