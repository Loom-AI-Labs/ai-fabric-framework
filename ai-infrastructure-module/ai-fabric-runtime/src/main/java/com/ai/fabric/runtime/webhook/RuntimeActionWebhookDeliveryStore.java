package com.ai.fabric.runtime.webhook;

import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class RuntimeActionWebhookDeliveryStore {

    private static final int CLAIM_BATCH_SIZE = 10;
    private static final Duration CLAIM_STALE_AFTER = Duration.ofMinutes(2);
    private static final List<String> READY_STATUSES = List.of(
        ActionWebhookDeliveryEntity.STATUS_PENDING,
        ActionWebhookDeliveryEntity.STATUS_RETRY_PENDING
    );

    private final ActionWebhookDeliveryRepository deliveryRepository;
    private final Clock clock;

    public RuntimeActionWebhookDeliveryStore(ActionWebhookDeliveryRepository deliveryRepository,
                                             Clock clock) {
        this.deliveryRepository = deliveryRepository;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Transactional
    public List<ActionWebhookDeliveryEntity> claimReadyDeliveries() {
        Instant now = Instant.now(clock);
        Instant staleBefore = now.minus(CLAIM_STALE_AFTER);
        List<ActionWebhookDeliveryEntity> deliveries = deliveryRepository.findDispatchableForUpdate(
            READY_STATUSES,
            ActionWebhookDeliveryEntity.STATUS_IN_PROGRESS,
            now,
            staleBefore,
            PageRequest.of(0, CLAIM_BATCH_SIZE)
        );
        for (ActionWebhookDeliveryEntity delivery : deliveries) {
            delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_IN_PROGRESS);
            delivery.setClaimedAt(now);
            delivery.setUpdatedAt(now);
        }
        return deliveries.stream().map(this::snapshot).toList();
    }

    @Transactional
    public void markDelivered(String deliveryId, Integer statusCode) {
        deliveryRepository.findById(deliveryId).ifPresent(delivery -> {
            Instant now = Instant.now(clock);
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_DELIVERED);
            delivery.setLastStatusCode(statusCode);
            delivery.setLastError(null);
            delivery.setDeliveredAt(now);
            delivery.setClaimedAt(null);
            delivery.setUpdatedAt(now);
        });
    }

    @Transactional
    public void markRetryPending(String deliveryId,
                                 Integer statusCode,
                                 String message,
                                 int maxAttempts,
                                 Duration nextBackoff) {
        deliveryRepository.findById(deliveryId).ifPresent(delivery -> {
            Instant now = Instant.now(clock);
            int attempts = delivery.getAttemptCount() + 1;
            delivery.setAttemptCount(attempts);
            delivery.setLastStatusCode(statusCode);
            delivery.setLastError(message);
            delivery.setClaimedAt(null);
            delivery.setUpdatedAt(now);
            if (attempts >= maxAttempts) {
                delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_FAILED);
                delivery.setNextAttemptAt(now);
                return;
            }
            delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_RETRY_PENDING);
            delivery.setNextAttemptAt(now.plus(nextBackoff));
        });
    }

    @Transactional
    public void markFailed(String deliveryId, Integer statusCode, String message) {
        deliveryRepository.findById(deliveryId).ifPresent(delivery -> {
            Instant now = Instant.now(clock);
            delivery.setAttemptCount(delivery.getAttemptCount() + 1);
            delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_FAILED);
            delivery.setLastStatusCode(statusCode);
            delivery.setLastError(message);
            delivery.setClaimedAt(null);
            delivery.setUpdatedAt(now);
            delivery.setNextAttemptAt(now);
        });
    }

    private ActionWebhookDeliveryEntity snapshot(ActionWebhookDeliveryEntity source) {
        ActionWebhookDeliveryEntity copy = new ActionWebhookDeliveryEntity();
        copy.setId(source.getId());
        copy.setActionName(source.getActionName());
        copy.setEventType(source.getEventType());
        copy.setTargetRef(source.getTargetRef());
        copy.setUrlSecretRef(source.getUrlSecretRef());
        copy.setSigningSecretRef(source.getSigningSecretRef());
        copy.setTimeoutMs(source.getTimeoutMs());
        copy.setMaxAttempts(source.getMaxAttempts());
        copy.setAttemptCount(source.getAttemptCount());
        copy.setStatus(source.getStatus());
        copy.setDeploymentId(source.getDeploymentId());
        copy.setConversationId(source.getConversationId());
        copy.setRequestId(source.getRequestId());
        copy.setPayloadJson(source.getPayloadJson());
        copy.setNextAttemptAt(source.getNextAttemptAt());
        copy.setClaimedAt(source.getClaimedAt());
        copy.setCreatedAt(source.getCreatedAt());
        copy.setUpdatedAt(source.getUpdatedAt());
        return copy;
    }
}
