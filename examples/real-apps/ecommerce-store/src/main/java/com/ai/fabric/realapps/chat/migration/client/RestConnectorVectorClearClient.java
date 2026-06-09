package com.ai.fabric.realapps.chat.migration.client;

import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Domain API -> REST connector admin client for clearing runtime vectors.
 *
 * <p>This keeps the domain API decoupled from runtime deployment details. The REST connector
 * may proxy this call to runtime when {@code rest-connector.runtime-proxy.enabled=true}.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestConnectorVectorClearClient {

    private final RestTemplateBuilder restTemplateBuilder;

    /**
     * Base URL of the REST connector. Reuses the existing indexing base URL config
     * to avoid introducing additional env vars.
     */
    @Value("${connector.indexing.runtime-base-url:}")
    private String restConnectorBaseUrl;

    /**
     * Optional: REST connector inbound API key (if enabled).
     */
    @Value("${connector.indexing.api-key:}")
    private String restConnectorApiKey;

    @Value("${connector.indexing.api-key-header:X-AIFABRIC-API-KEY}")
    private String restConnectorApiKeyHeader;

    public Map<String, Object> clearRuntimeVectors() {
        return clearRuntimeVectors(null);
    }

    public Map<String, Object> clearRuntimeVectors(@Nullable String reason) {
        if (!StringUtils.hasText(restConnectorBaseUrl)) {
            return Map.of(
                "success", false,
                "skipped", true,
                "message", "connector.indexing.runtime-base-url is blank; skipping runtime vector clear"
            );
        }

        URI uri;
        try {
            String path = "/api/admin/migration/clear?confirm=true";
            uri = URI.create(trimTrailingSlash(restConnectorBaseUrl.trim()) + path);
        } catch (IllegalArgumentException ex) {
            return Map.of(
                "success", false,
                "skipped", true,
                "message", "Invalid REST connector base URL: " + ex.getMessage()
            );
        }

        if (!uri.isAbsolute()) {
            return Map.of(
                "success", false,
                "skipped", true,
                "message", "REST connector base URL must be absolute (include scheme, e.g. https://...). Got: " + restConnectorBaseUrl
            );
        }

        RestTemplate restTemplate = restTemplateBuilder.build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(restConnectorApiKey)) {
            String headerName = StringUtils.hasText(restConnectorApiKeyHeader) ? restConnectorApiKeyHeader.trim() : "X-AIFABRIC-API-KEY";
            headers.set(headerName, restConnectorApiKey.trim());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("confirm", true);
        if (StringUtils.hasText(reason)) {
            payload.put("reason", reason.trim());
        }

        try {
            Object responseBody = restTemplate.postForEntity(uri, new HttpEntity<>(payload, headers), Object.class).getBody();
            return Map.of(
                "success", true,
                "skipped", false,
                "uri", uri.toString(),
                "response", responseBody
            );
        } catch (RestClientException ex) {
            log.warn("Runtime vector clear via REST connector failed (uri={}): {}", uri, ex.getMessage());
            return Map.of(
                "success", false,
                "skipped", false,
                "uri", uri.toString(),
                "message", ex.getMessage()
            );
        }
    }

    private String trimTrailingSlash(String url) {
        if (url.endsWith("/")) {
            return url.substring(0, url.length() - 1);
        }
        return url;
    }
}
