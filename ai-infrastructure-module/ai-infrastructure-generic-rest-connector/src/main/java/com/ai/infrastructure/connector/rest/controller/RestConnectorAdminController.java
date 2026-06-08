package com.ai.infrastructure.connector.rest.controller;

import com.ai.infrastructure.connector.rest.config.RestConnectorServiceProperties;
import com.ai.infrastructure.connector.rest.config.RestRoutingConfig;
import com.ai.infrastructure.connector.rest.util.TraceContextSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admin endpoints to verify which routes are loaded at runtime (debugging "actions not routed" issues).
 *
 * <p>Security:
 * These endpoints are protected by the same inbound auth filter as {@code POST /actions/execute}
 * when {@code connector.inbound-auth.allow-unauthenticated=false}.</p>
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class RestConnectorAdminController {

    private final RestRoutingConfig routingConfig;
    private final RestConnectorServiceProperties serviceProperties;

    @GetMapping("/overview")
    public ResponseEntity<?> overview() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("routingConfigLocation", serviceProperties != null ? serviceProperties.getRoutingConfigLocation() : null);

        RestRoutingConfig.Connector connector = routingConfig != null ? routingConfig.getConnector() : null;
        body.put("connector", sanitizeConnector(connector));
        RestRoutingConfig.Authz authz = routingConfig != null ? routingConfig.getAuthz() : null;
        body.put("authz", sanitizeAuthz(authz));
        body.put("traceContext", traceContextDiagnostics());

        Map<String, RestRoutingConfig.ActionRoute> actions = routingConfig != null ? routingConfig.getActions() : null;
        List<Map<String, Object>> actionList = toActionSummaries(actions);
        body.put("actionsCount", actionList.size());
        body.put("actions", actionList);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/actions/overview")
    public ResponseEntity<?> actionsOverview() {
        Map<String, RestRoutingConfig.ActionRoute> actions = routingConfig != null ? routingConfig.getActions() : null;
        List<Map<String, Object>> actionList = toActionSummaries(actions);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("count", actionList.size());
        body.put("actions", actionList);
        return ResponseEntity.ok(body);
    }

    @GetMapping("/actions/{actionId}")
    public ResponseEntity<?> action(@PathVariable("actionId") String actionId) {
        if (!StringUtils.hasText(actionId)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of(
                "success", false,
                "message", "actionId is required"
            ));
        }

        Map<String, RestRoutingConfig.ActionRoute> actions = routingConfig != null ? routingConfig.getActions() : null;
        RestRoutingConfig.ActionRoute route = actions != null ? actions.get(actionId.trim()) : null;
        if (route == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "success", false,
                "message", "Action route not found"
            ));
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("action", sanitizeAction(actionId.trim(), route));
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> sanitizeConnector(RestRoutingConfig.Connector connector) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (connector == null) {
            return out;
        }

        RestRoutingConfig.InboundAuth inbound = connector.getInboundAuth();
        Map<String, Object> inboundOut = new LinkedHashMap<>();
        if (inbound != null) {
            inboundOut.put("allowUnauthenticated", inbound.isAllowUnauthenticated());

            RestRoutingConfig.ApiKey apiKey = inbound.getApiKey();
            Map<String, Object> apiKeyOut = new LinkedHashMap<>();
            if (apiKey != null) {
                apiKeyOut.put("enabled", apiKey.isEnabled());
                apiKeyOut.put("header", apiKey.getHeader());
                apiKeyOut.put("valueConfigured", StringUtils.hasText(apiKey.getValue()));
            }
            inboundOut.put("apiKey", apiKeyOut);
        }
        out.put("inboundAuth", inboundOut);

        RestRoutingConfig.Upstream upstream = connector.getUpstream();
        Map<String, Object> upstreamOut = new LinkedHashMap<>();
        if (upstream != null) {
            upstreamOut.put("baseUrl", upstream.getBaseUrl());

            RestRoutingConfig.UpstreamAuth auth = upstream.getAuth();
            Map<String, Object> authOut = new LinkedHashMap<>();
            if (auth != null) {
                authOut.put("type", auth.getType() != null ? auth.getType().name() : null);
                authOut.put("header", auth.getHeader());
                authOut.put("valueConfigured", StringUtils.hasText(auth.getValue()));
            }
            upstreamOut.put("auth", authOut);
        }
        out.put("upstream", upstreamOut);

        RestRoutingConfig.Http http = connector.getHttp();
        Map<String, Object> httpOut = new LinkedHashMap<>();
        if (http != null) {
            httpOut.put("connectTimeoutMs", http.getConnectTimeoutMs());
            httpOut.put("timeoutMs", http.getTimeoutMs());
            RestRoutingConfig.Retry retry = http.getRetry();
            Map<String, Object> retryOut = new LinkedHashMap<>();
            if (retry != null) {
                retryOut.put("enabled", retry.isEnabled());
                retryOut.put("maxAttempts", retry.getMaxAttempts());
                retryOut.put("backoffMs", retry.getBackoffMs());
                retryOut.put("retryOn", retry.getRetryOn());
            }
            httpOut.put("retry", retryOut);
        }
        out.put("http", httpOut);

        RestRoutingConfig.Idempotency idempotency = connector.getIdempotency();
        Map<String, Object> idempotencyOut = new LinkedHashMap<>();
        if (idempotency != null) {
            idempotencyOut.put("enabled", idempotency.isEnabled());
            idempotencyOut.put("ttlSeconds", idempotency.getTtlSeconds());
            idempotencyOut.put("inProgressMaxWaitMs", idempotency.getInProgressMaxWaitMs());
        }
        out.put("idempotency", idempotencyOut);

        return out;
    }

    private static Map<String, Object> sanitizeAuthz(RestRoutingConfig.Authz authz) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (authz == null) {
            return out;
        }
        out.put("enabled", authz.isEnabled());
        out.put("path", authz.getPath());

        RestRoutingConfig.Upstream upstream = authz.getUpstream();
        Map<String, Object> upstreamOut = new LinkedHashMap<>();
        if (upstream != null) {
            upstreamOut.put("baseUrl", upstream.getBaseUrl());

            RestRoutingConfig.UpstreamAuth auth = upstream.getAuth();
            Map<String, Object> authOut = new LinkedHashMap<>();
            if (auth != null) {
                authOut.put("type", auth.getType() != null ? auth.getType().name() : null);
                authOut.put("header", auth.getHeader());
                authOut.put("valueConfigured", StringUtils.hasText(auth.getValue()));
            }
            upstreamOut.put("auth", authOut);
        }
        out.put("upstream", upstreamOut);

        RestRoutingConfig.AuthzHttp http = authz.getHttp();
        Map<String, Object> httpOut = new LinkedHashMap<>();
        if (http != null) {
            httpOut.put("connectTimeoutMs", http.getConnectTimeoutMs());
            httpOut.put("timeoutMs", http.getTimeoutMs());
        }
        out.put("http", httpOut);

        return out;
    }

    private static Map<String, Object> traceContextDiagnostics() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verifiedAuthContextSupported", true);
        out.put("preferredIdentitySource", "authContext");
        out.put("actionPreflightAuthorizationSupported", true);
        out.put("forwardedVerifiedAuthHeaders", TraceContextSupport.VERIFIED_AUTH_FORWARD_HEADERS);
        out.put("templateTraceKeys", TraceContextSupport.TEMPLATE_TRACE_KEYS);
        out.put("guidance",
            "Connector routes forward canonical verified auth context headers only. Sensitive action routes should enable authz preflight and rely on authContext rather than any caller-supplied identity aliases.");
        return out;
    }

    private static List<Map<String, Object>> toActionSummaries(Map<String, RestRoutingConfig.ActionRoute> actions) {
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        actions.forEach((actionId, route) -> {
            if (!StringUtils.hasText(actionId) || route == null) {
                return;
            }
            out.add(sanitizeAction(actionId.trim(), route));
        });
        out.sort(Comparator.comparing(o -> Objects.toString(o.get("actionId"), "")));
        return out;
    }

    private static Map<String, Object> sanitizeAction(String actionId, RestRoutingConfig.ActionRoute route) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("actionId", actionId);
        out.put("method", route.getMethod());
        out.put("url", route.getUrl());
        out.put("path", route.getPath());
        out.put("timeoutMs", route.getTimeoutMs());

        // Avoid leaking secrets via headers; show keys only.
        List<String> headerKeys = route.getHeaders() != null ? route.getHeaders().keySet().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .sorted()
            .toList() : List.of();
        out.put("headers", headerKeys);

        RestRoutingConfig.Request req = route.getRequest();
        out.put("hasQueryTemplate", req != null && req.getQuery() != null && !req.getQuery().isEmpty());
        out.put("hasBodyTemplate", req != null && req.getBody() != null);

        RestRoutingConfig.Response resp = route.getResponse();
        out.put("successHttpStatus", resp != null ? resp.getSuccessHttpStatus() : List.of());
        out.put("hasResultTemplate", resp != null && resp.getResult() != null);
        out.put("hasMessageTemplate", resp != null && StringUtils.hasText(resp.getMessage()));
        out.put("hasPinnedTargetsTemplate", resp != null && resp.getPinnedTargets() != null);
        RestRoutingConfig.ActionAuthz authz = route.getAuthz();
        out.put("authzEnabled", authz != null && authz.isEnabled());
        out.put("authzResourceIdTemplate", authz != null ? authz.getResourceId() : null);
        out.put("authzOperationType", authz != null ? authz.getOperationType() : null);
        out.put("authzRequestedScopes", authz != null ? authz.getRequestedScopes() : List.of());
        out.put("hasAuthzRequestContextTemplate", authz != null && authz.getRequestContext() != null);

        return out;
    }
}
