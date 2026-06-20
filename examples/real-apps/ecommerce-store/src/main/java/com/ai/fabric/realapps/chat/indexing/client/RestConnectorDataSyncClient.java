package com.ai.fabric.realapps.chat.indexing.client;

import com.ai.fabric.realapps.chat.indexing.ConnectorIndexingProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "connector.indexing", name = "enabled", havingValue = "true")
public class RestConnectorDataSyncClient {

    private final RestTemplate restTemplate;
    private final ConnectorIndexingProperties properties;
    private final Clock clock = Clock.systemUTC();

    public void upsertProduct(Map<String, Object> productEntity) {
        Objects.requireNonNull(productEntity, "productEntity is required");
        String sku = Objects.toString(productEntity.get("sku"), null);
        if (!StringUtils.hasText(sku)) {
            throw new IllegalArgumentException("productEntity.sku is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vectorSpace", "product");
        payload.put("id", sku);
        payload.put("entity", productEntity);
        payload.put("trace", trace("product-upsert", sku));

        postJson("/api/ai/data-sync/upsert", payload);
    }

    public void deleteProductBySku(String sku) {
        if (!StringUtils.hasText(sku)) {
            throw new IllegalArgumentException("sku is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vectorSpace", "product");
        payload.put("id", sku.trim());
        payload.put("trace", trace("product-delete", sku));

        postJson("/api/ai/data-sync/delete", payload);
    }

    public void upsertPolicy(String policyExternalId, Map<String, Object> policyEntity) {
        Objects.requireNonNull(policyEntity, "policyEntity is required");
        if (!StringUtils.hasText(policyExternalId)) {
            throw new IllegalArgumentException("policyExternalId is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vectorSpace", "policy");
        payload.put("id", policyExternalId.trim());
        payload.put("entity", policyEntity);
        payload.put("trace", trace("policy-upsert", policyExternalId));

        postJson("/api/ai/data-sync/upsert", payload);
    }

    public void deletePolicy(String policyExternalId) {
        if (!StringUtils.hasText(policyExternalId)) {
            throw new IllegalArgumentException("policyExternalId is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vectorSpace", "policy");
        payload.put("id", policyExternalId.trim());
        payload.put("trace", trace("policy-delete", policyExternalId));

        postJson("/api/ai/data-sync/delete", payload);
    }

    public void upsertReview(String reviewExternalId, Map<String, Object> reviewEntity) {
        Objects.requireNonNull(reviewEntity, "reviewEntity is required");
        if (!StringUtils.hasText(reviewExternalId)) {
            throw new IllegalArgumentException("reviewExternalId is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vectorSpace", "review");
        payload.put("id", reviewExternalId.trim());
        payload.put("entity", reviewEntity);
        payload.put("trace", trace("review-upsert", reviewExternalId));

        postJson("/api/ai/data-sync/upsert", payload);
    }

    public void deleteReview(String reviewExternalId) {
        if (!StringUtils.hasText(reviewExternalId)) {
            throw new IllegalArgumentException("reviewExternalId is required");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("vectorSpace", "review");
        payload.put("id", reviewExternalId.trim());
        payload.put("trace", trace("review-delete", reviewExternalId));

        postJson("/api/ai/data-sync/delete", payload);
    }

    private void postJson(String path, Map<String, Object> payload) {
        if (!properties.enabled()) {
            return;
        }

        String baseUrl = properties.runtimeBaseUrl();
        if (!StringUtils.hasText(baseUrl)) {
            log.warn("Connector indexing is enabled but 'connector.indexing.runtime-base-url' is blank; skipping {}", path);
            return;
        }

        URI uri = URI.create(trimTrailingSlash(baseUrl.trim()) + path);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        if (StringUtils.hasText(properties.apiKey())) {
            String headerName = StringUtils.hasText(properties.apiKeyHeader())
                ? properties.apiKeyHeader().trim()
                : "X-AIFABRIC-API-KEY";
            headers.set(headerName, properties.apiKey().trim());
        }

        try {
            restTemplate.postForEntity(uri, new HttpEntity<>(payload, headers), String.class);
        } catch (RestClientException ex) {
            log.warn("Data Sync call failed (uri={}): {}", uri, ex.getMessage());
        }
    }

    private Map<String, Object> trace(String prefix, String entityId) {
        String requestId = prefix + "-" + safe(entityId) + "-" + Instant.now(clock).toEpochMilli() + "-" + UUID.randomUUID();
        Map<String, Object> authContext = new LinkedHashMap<>();
        authContext.put("subjectId", "system:platform-ecommerce-store");
        authContext.put("subjectType", "SYSTEM_PROCESS");
        authContext.put("authMode", "PRIVATE_RUNTIME_BACKEND_MEDIATED");
        authContext.put("callerType", "SYSTEM_PROCESS");
        authContext.put("sessionId", "connector");
        authContext.put("deploymentId", "ecommerce-store-local");
        authContext.put("customerId", "demo-commerce-customer");
        authContext.put("tenantId", "demo-commerce-tenant");
        authContext.put("issuer", "platform-ecommerce-store");
        authContext.put("grantedScopes", List.of("data-sync:upsert", "data-sync:delete"));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", "ecommerce-store");
        metadata.put("operation", prefix);
        metadata.put("entityId", safe(entityId));

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("requestId", requestId);
        trace.put("metadata", metadata);
        trace.put("authContext", authContext);
        return trace;
    }

    private String safe(String raw) {
        if (raw == null) {
            return "null";
        }
        return raw.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
