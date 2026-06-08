package com.ai.infrastructure.connector.rest.service;

import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import com.ai.infrastructure.connector.rest.util.UrlBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

@Slf4j
@Service
public class RestAuthzProxyService {

    private final RestRoutingConfig config;
    private final HttpClient httpClient;

    public RestAuthzProxyService(RestRoutingConfig config) {
        this.config = config;

        RestRoutingConfig.Authz authz = config != null ? config.getAuthz() : null;
        RestRoutingConfig.AuthzHttp http = authz != null ? authz.getHttp() : null;
        int connectTimeoutMs = http != null ? http.getConnectTimeoutMs() : 500;
        Duration connectTimeout = Duration.ofMillis(Math.max(100, connectTimeoutMs));

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(connectTimeout)
            .build();
    }

    public ProxyResponse forward(String jsonBody) {
        RestRoutingConfig.Authz authz = config != null ? config.getAuthz() : null;
        if (authz == null || !authz.isEnabled()) {
            throw new IllegalStateException("Authz proxy is disabled.");
        }

        RestRoutingConfig.Upstream upstream = authz.getUpstream();
        String baseUrl = upstream != null ? upstream.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            // Convenience: allow reusing the connector upstream baseUrl if authz uses the same upstream service.
            RestRoutingConfig.Connector connector = config.getConnector();
            RestRoutingConfig.Upstream connectorUpstream = connector != null ? connector.getUpstream() : null;
            baseUrl = connectorUpstream != null ? connectorUpstream.getBaseUrl() : null;
        }
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("authz.upstream.base-url is required when authz.enabled=true.");
        }

        String path = StringUtils.hasText(authz.getPath()) ? authz.getPath().trim() : "/api/authz/check";
        if (path.contains("://")) {
            throw new IllegalStateException("authz.path must be a relative path (no scheme).");
        }

        URI uri = URI.create(UrlBuilder.join(baseUrl.trim(), path));

        RestRoutingConfig.AuthzHttp http = authz.getHttp();
        int timeoutMs = http != null ? http.getTimeoutMs() : 1500;
        Duration timeout = Duration.ofMillis(Math.max(100, timeoutMs));

        String body = StringUtils.hasText(jsonBody) ? jsonBody : "{}";

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
            .timeout(timeout)
            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        RestRoutingConfig.UpstreamAuth upstreamAuth = upstream != null ? upstream.getAuth() : null;
        if (upstreamAuth != null && upstreamAuth.getType() == RestRoutingConfig.UpstreamAuth.AuthType.API_KEY) {
            String header = upstreamAuth.getHeader();
            String value = upstreamAuth.getValue();
            if (StringUtils.hasText(header) && StringUtils.hasText(value)) {
                builder.header(header.trim(), value.trim());
            }
        }

        long start = System.currentTimeMillis();
        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response != null ? response.statusCode() : 0;
            String respBody = response != null ? response.body() : null;
            long tookMs = System.currentTimeMillis() - start;
            log.debug("Authz proxy -> upstream POST (status={}, tookMs={})", status, tookMs);
            return new ProxyResponse(status, respBody);
        } catch (HttpTimeoutException ex) {
            throw new IllegalStateException("Upstream authz request timed out.");
        } catch (InterruptedException ex) {
            // Preserve the interrupt flag for cooperative cancellation.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Upstream authz request interrupted.");
        } catch (Exception ex) {
            String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase(Locale.ROOT) : "";
            boolean timeoutErr = msg.contains("timed out") || msg.contains("timeout");
            String reason = timeoutErr ? "Upstream authz request timed out." : "Upstream authz service unavailable.";
            throw new IllegalStateException(reason);
        }
    }

    public record ProxyResponse(int status, String body) { }
}
