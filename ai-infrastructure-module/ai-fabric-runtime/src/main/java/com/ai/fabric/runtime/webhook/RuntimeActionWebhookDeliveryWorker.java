package com.ai.fabric.runtime.webhook;

import com.ai.infrastructure.http.OutboundHttpExecutionRequest;
import com.ai.infrastructure.http.OutboundHttpExecutionResponse;
import com.ai.infrastructure.http.OutboundHttpExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Service
public class RuntimeActionWebhookDeliveryWorker {

    private static final Duration MAX_RETRY_BACKOFF = Duration.ofMinutes(5);

    private final RuntimeActionWebhookDeliveryStore deliveryStore;
    private final OutboundHttpExecutor outboundHttpExecutor;
    private final RuntimeWebhookSecretResolver secretResolver;
    private final Clock clock;

    public RuntimeActionWebhookDeliveryWorker(RuntimeActionWebhookDeliveryStore deliveryStore,
                                              OutboundHttpExecutor outboundHttpExecutor,
                                              RuntimeWebhookSecretResolver secretResolver,
                                              Clock clock) {
        this.deliveryStore = deliveryStore;
        this.outboundHttpExecutor = outboundHttpExecutor;
        this.secretResolver = secretResolver;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Scheduled(fixedDelayString = "${ai.webhooks.dispatch.fixed-delay-ms:5000}")
    public void dispatchReadyDeliveries() {
        for (ActionWebhookDeliveryEntity delivery : deliveryStore.claimReadyDeliveries()) {
            dispatchSingleDelivery(delivery);
        }
    }

    private void dispatchSingleDelivery(ActionWebhookDeliveryEntity delivery) {
        try {
            String targetUrl = validateTargetUrl(secretResolver.resolveRequired(delivery.getUrlSecretRef()));
            OutboundHttpExecutionResponse response = outboundHttpExecutor.execute(
                new OutboundHttpExecutionRequest(
                    targetUrl,
                    HttpMethod.POST,
                    buildHeaders(delivery),
                    delivery.getPayloadJson(),
                    Duration.ofMillis(resolvePositive(delivery.getTimeoutMs(), 3_000)),
                    Duration.ofMillis(resolvePositive(delivery.getTimeoutMs(), 3_000))
                )
            );
            int statusCode = response != null ? response.statusCode() : 0;
            if (statusCode >= 200 && statusCode < 300) {
                deliveryStore.markDelivered(delivery.getId(), statusCode);
                return;
            }
            if (isRetryableStatus(statusCode)) {
                deliveryStore.markRetryPending(
                    delivery.getId(),
                    statusCode,
                    truncate("Webhook delivery returned HTTP " + statusCode + "."),
                    resolvePositive(delivery.getMaxAttempts(), 5),
                    backoffForAttempt(delivery.getAttemptCount() + 1)
                );
                return;
            }
            deliveryStore.markFailed(
                delivery.getId(),
                statusCode,
                truncate("Webhook delivery returned non-retryable HTTP " + statusCode + ".")
            );
        } catch (IllegalArgumentException | IllegalStateException ex) {
            deliveryStore.markFailed(delivery.getId(), null, truncate(ex.getMessage()));
        } catch (Exception ex) {
            log.warn("Webhook delivery '{}' failed: {}", delivery.getId(), ex.getMessage());
            deliveryStore.markRetryPending(
                delivery.getId(),
                null,
                truncate("Webhook delivery failed: " + blankToFallback(ex.getMessage(), ex.getClass().getSimpleName())),
                resolvePositive(delivery.getMaxAttempts(), 5),
                backoffForAttempt(delivery.getAttemptCount() + 1)
            );
        }
    }

    private Map<String, String> buildHeaders(ActionWebhookDeliveryEntity delivery) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("User-Agent", "ai-fabric-runtime/webhook");
        headers.put("X-AI-Fabric-Delivery-Id", delivery.getId());
        headers.put("X-AI-Fabric-Event-Type", delivery.getEventType());
        String timestamp = Instant.now(clock).toString();
        headers.put("X-AI-Fabric-Delivery-Timestamp", timestamp);
        if (StringUtils.hasText(delivery.getSigningSecretRef())) {
            String secret = secretResolver.resolveRequired(delivery.getSigningSecretRef());
            headers.put("X-AI-Fabric-Signature", sign(secret, timestamp, delivery.getPayloadJson()));
        }
        return headers;
    }

    private String sign(String secret, String timestamp, String body) {
        try {
            String message = timestamp + "\n" + (body != null ? body : "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute webhook HMAC signature: " + ex.getMessage(), ex);
        }
    }

    private String validateTargetUrl(String rawUrl) {
        if (!StringUtils.hasText(rawUrl)) {
            throw new IllegalArgumentException("Webhook target URL is required.");
        }
        URI uri = URI.create(rawUrl.trim());
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "";
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("Webhook target URL must start with http:// or https://.");
        }
        if (!StringUtils.hasText(uri.getHost())) {
            throw new IllegalArgumentException("Webhook target URL host is required.");
        }
        if (StringUtils.hasText(uri.getUserInfo())) {
            throw new IllegalArgumentException("Webhook target URL must not include user info.");
        }
        if (StringUtils.hasText(uri.getFragment())) {
            throw new IllegalArgumentException("Webhook target URL must not include a fragment.");
        }
        return uri.toString();
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 408
            || statusCode == 425
            || statusCode == 429
            || statusCode == 0
            || statusCode >= 500;
    }

    private Duration backoffForAttempt(int attempts) {
        long seconds = Math.min(MAX_RETRY_BACKOFF.getSeconds(), (long) Math.pow(2, Math.max(0, attempts - 1)) * 10L);
        return Duration.ofSeconds(Math.max(10L, seconds));
    }

    private int resolvePositive(Integer value, int fallback) {
        return value != null && value > 0 ? value : fallback;
    }

    private String truncate(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String trimmed = message.trim();
        return trimmed.length() > 4000 ? trimmed.substring(0, 4000) : trimmed;
    }

    private String blankToFallback(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
