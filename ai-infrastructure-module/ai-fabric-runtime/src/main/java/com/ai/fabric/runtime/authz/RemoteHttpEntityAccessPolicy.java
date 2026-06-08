package com.ai.fabric.runtime.authz;

import com.ai.fabric.runtime.config.RuntimeAuthzProperties;
import com.ai.infrastructure.access.policy.EntityAccessPolicy;
import com.ai.infrastructure.dto.AIAccessSubjectContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationContextMetadataKeys;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Remote HTTP-backed {@link EntityAccessPolicy} for runtime product deployments.
 *
 * <p>Fail-closed behavior:
 * any error (misconfiguration, timeout, non-2xx, invalid JSON) results in {@code false}.</p>
 */
@Slf4j
public class RemoteHttpEntityAccessPolicy implements EntityAccessPolicy {

    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final String AUTHZ_CONTRACT_VERSION = "AUTH_CONTEXT_V1";
    private static final Set<String> CANONICAL_METADATA_KEYS = Set.of(
        "sessionId",
        "entryPoint",
        "authenticated",
        "ipAddress",
        OrchestrationContextMetadataKeys.SUBJECT_ID,
        OrchestrationContextMetadataKeys.SUBJECT_TYPE,
        OrchestrationContextMetadataKeys.AUTH_MODE,
        OrchestrationContextMetadataKeys.CALLER_TYPE,
        OrchestrationContextMetadataKeys.AUTH_ISSUER,
        OrchestrationContextMetadataKeys.AUTH_AUDIENCES,
        OrchestrationContextMetadataKeys.AUTH_EXPIRES_AT,
        OrchestrationContextMetadataKeys.DEPLOYMENT_ID,
        OrchestrationContextMetadataKeys.CUSTOMER_ID,
        OrchestrationContextMetadataKeys.TENANT_ID,
        OrchestrationContextMetadataKeys.GRANTED_SCOPES,
        OrchestrationContextMetadataKeys.REQUESTED_SCOPES
    );

    private final ObjectMapper objectMapper;
    private final URI endpointUri;
    private final Duration timeout;
    private final HttpClient httpClient;
    private final Map<String, String> defaultHeaders;

    public RemoteHttpEntityAccessPolicy(RuntimeAuthzProperties properties,
                                        ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");

        RuntimeAuthzProperties.Remote remote = properties != null ? properties.getRemote() : null;
        String baseUrl = remote != null ? remote.getBaseUrl() : null;
        String path = remote != null ? remote.getPath() : null;
        this.endpointUri = buildEndpointUri(baseUrl, path);

        int connectTimeoutMs = remote != null ? Math.max(100, remote.getConnectTimeoutMs()) : 500;
        this.timeout = Duration.ofMillis(remote != null ? Math.max(100, remote.getTimeoutMs()) : 1500);

        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(connectTimeoutMs))
            .build();

        this.defaultHeaders = buildDefaultHeaders(properties);
    }

    @Override
    public boolean canAccess(AIAccessSubjectContext authContext, Map<String, Object> entity) {
        if (entity == null || endpointUri == null || authContext == null) {
            return false;
        }
        if (!StringUtils.hasText(authContext.getSubjectId())) {
            return false;
        }

        Map<String, Object> metadata = entity.get("metadata") instanceof Map<?, ?> raw ? toStringKeyMap(raw) : Map.of();
        RemoteAuthzCheckRequest request = buildRequest(authContext, metadata, entity);
        String json;
        try {
            json = objectMapper.writeValueAsString(request);
        } catch (Exception ex) {
            log.debug("Remote authz request serialization failed: {}", ex.getMessage());
            return false;
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(endpointUri)
            .timeout(timeout)
            .header("Content-Type", CONTENT_TYPE_JSON)
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        defaultHeaders.forEach(builder::header);

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response != null ? response.statusCode() : 0;
            if (status < 200 || status >= 300) {
                return false;
            }
            String body = response.body();
            if (!StringUtils.hasText(body)) {
                return false;
            }
            RemoteAuthzCheckResponse parsed = objectMapper.readValue(body, RemoteAuthzCheckResponse.class);
            return parsed != null && Boolean.TRUE.equals(parsed.granted());
        } catch (InterruptedException ex) {
            // Preserve the interrupt flag for cooperative cancellation.
            Thread.currentThread().interrupt();
            log.debug("Remote authz call interrupted: {}", ex.getMessage());
            return false;
        } catch (Exception ex) {
            // Deny on timeout/unavailability/unparseable payload.
            log.debug("Remote authz call failed: {}", ex.getMessage());
            return false;
        }
    }

    @Override
    public void logAccessDenied(AIAccessSubjectContext authContext, Map<String, Object> entity, String reason) {
        if (entity == null || authContext == null) {
            return;
        }
        String resourceId = Objects.toString(entity.get("resourceId"), "");
        String op = Objects.toString(entity.get("operationType"), "");
        log.debug("Access denied (remote authz) subjectId={}, resourceId={}, operationType={}, reason={}",
            authContext.getSubjectId(), resourceId, op, reason);
    }

    private RemoteAuthzCheckRequest buildRequest(AIAccessSubjectContext authContext,
                                                 Map<String, Object> metadata,
                                                 Map<String, Object> entity) {
        Map<String, Object> sanitizedMetadata = sanitizeMetadataForRequest(metadata);
        Map<String, Object> userAttributes = entity.get("userAttributes") instanceof Map<?, ?> raw ? toStringKeyMap(raw) : Map.of();
        List<String> grantedScopes = authContext.getGrantedScopes() != null && !authContext.getGrantedScopes().isEmpty()
            ? List.copyOf(authContext.getGrantedScopes())
            : List.of();
        List<String> requestedScopes = toStringList(metadata.get(OrchestrationContextMetadataKeys.REQUESTED_SCOPES));
        String subjectType = firstNonBlank(authContext.getSubjectType(), null);
        String authMode = firstNonBlank(authContext.getAuthMode(), null);
        String callerType = firstNonBlank(authContext.getCallerType(), null);
        String sessionId = resolveEffectiveSessionId(authContext);
        String deploymentId = firstNonBlank(authContext.getDeploymentId(), null);
        String customerId = firstNonBlank(authContext.getCustomerId(), null);
        String tenantId = firstNonBlank(authContext.getTenantId(), null);
        String issuer = firstNonBlank(authContext.getIssuer(), null);
        List<String> audiences = authContext.getAudiences() != null && !authContext.getAudiences().isEmpty()
            ? List.copyOf(authContext.getAudiences())
            : List.of();
        String expiresAt = firstNonBlank(authContext.getExpiresAt(), null);
        Map<String, Object> requestContext = buildRequestContext(entity, metadata);

        return new RemoteAuthzCheckRequest(
            AUTHZ_CONTRACT_VERSION,
            Objects.toString(entity.get("requestId"), null),
            legacyUserId(authContext),
            authContext.getSubjectId(),
            subjectType,
            authMode,
            callerType,
            sessionId,
            deploymentId,
            customerId,
            tenantId,
            issuer,
            grantedScopes,
            requestedScopes,
            Objects.toString(entity.get("resourceId"), "UNKNOWN"),
            Objects.toString(entity.get("operationType"), "READ"),
            requestContext,
            sanitizedMetadata,
            userAttributes,
            buildCompatibilityAliases(authContext, sessionId),
            new RemoteAuthzVerifiedAuthContext(
                authContext.getSubjectId(),
                subjectType,
                authMode,
                callerType,
                sessionId,
                deploymentId,
                customerId,
                tenantId,
                issuer,
                grantedScopes,
                audiences,
                expiresAt
            )
        );
    }

    private Map<String, String> buildCompatibilityAliases(AIAccessSubjectContext authContext, String sessionId) {
        Map<String, String> aliases = new LinkedHashMap<>();
        String userId = legacyUserId(authContext);
        if (StringUtils.hasText(userId)) {
            aliases.put("userId", userId.trim());
        }
        if (StringUtils.hasText(sessionId)) {
            aliases.put("sessionId", sessionId.trim());
        }
        return aliases.isEmpty() ? Map.of() : Map.copyOf(aliases);
    }

    private String legacyUserId(AIAccessSubjectContext authContext) {
        if (authContext == null || !StringUtils.hasText(authContext.getSubjectId())) {
            return null;
        }
        if ("ANONYMOUS_SESSION".equalsIgnoreCase(authContext.getSubjectType())) {
            return null;
        }
        return authContext.getSubjectId().trim();
    }

    private Map<String, Object> buildRequestContext(Map<String, Object> entity, Map<String, Object> metadata) {
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(out, "context", entity.get("context"));
        copyIfPresent(out, "purpose", entity.get("purpose"));
        if (entity.get("timestamp") != null) {
            out.put("timestamp", entity.get("timestamp").toString());
        }
        copyIfPresent(out, "entryPoint", metadata.get("entryPoint"));
        copyIfPresent(out, "authenticated", metadata.get("authenticated"));
        copyIfPresent(out, "ipAddress", metadata.get("ipAddress"));
        return out.isEmpty() ? Map.of() : Map.copyOf(out);
    }

    private void copyIfPresent(Map<String, Object> target, String key, Object value) {
        if (target == null || !StringUtils.hasText(key) || value == null) {
            return;
        }
        if (value instanceof String stringValue) {
            if (!StringUtils.hasText(stringValue)) {
                return;
            }
            target.put(key, stringValue.trim());
            return;
        }
        target.put(key, value);
    }

    private Map<String, String> buildDefaultHeaders(RuntimeAuthzProperties properties) {
        Map<String, String> headers = new LinkedHashMap<>();
        RuntimeAuthzProperties.OutboundAuth outbound = properties != null && properties.getRemote() != null ? properties.getRemote().getOutboundAuth() : null;
        RuntimeAuthzProperties.OutboundAuthType type = outbound != null ? outbound.getType() : RuntimeAuthzProperties.OutboundAuthType.INHERIT_ACTIONS_API_KEY;

        if (type == null || type == RuntimeAuthzProperties.OutboundAuthType.NONE) {
            return Map.of();
        }

        String header = outbound != null ? outbound.getApiKeyHeader() : null;
        String value = outbound != null ? outbound.getApiKeyValue() : null;
        if (StringUtils.hasText(header) && StringUtils.hasText(value)) {
            headers.put(header.trim(), value.trim());
        }
        return headers.isEmpty() ? Map.of() : Map.copyOf(headers);
    }

    private URI buildEndpointUri(String baseUrl, String path) {
        if (!StringUtils.hasText(baseUrl)) {
            log.warn("Remote authz is enabled but no base URL is configured (set ai.fabric.runtime.authz.remote.base-url or ai.actions.connector.base-url). Denying all access.");
            return null;
        }
        String base = baseUrl.trim();
        String p = StringUtils.hasText(path) ? path.trim() : "/api/authz/check";
        String joined = joinUrl(base, p);

        try {
            URI uri = URI.create(joined);
            String scheme = uri.getScheme();
            if (!StringUtils.hasText(scheme)) {
                throw new IllegalArgumentException("missing scheme");
            }
            String s = scheme.trim().toLowerCase();
            if (!"http".equals(s) && !"https".equals(s)) {
                throw new IllegalArgumentException("unsupported scheme '" + scheme + "'");
            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw new IllegalArgumentException("missing host");
            }
            return uri;
        } catch (Exception ex) {
            log.warn("Invalid remote authz URL '{}': {}. Denying all access.", joined, ex.getMessage());
            return null;
        }
    }

    private String joinUrl(String baseUrl, String path) {
        String base = baseUrl;
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        String p = path;
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return base + p;
    }

    private Map<String, Object> toStringKeyMap(Map<?, ?> raw) {
        if (raw == null || raw.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            out.put(entry.getKey().toString(), entry.getValue());
        }
        return out;
    }

    private List<String> toStringList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) {
            return List.of();
        }
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (Object item : iterable) {
            if (item == null) {
                continue;
            }
            String trimmed = item.toString().trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.isEmpty() ? List.of() : List.copyOf(out);
    }

    private Map<String, Object> sanitizeMetadataForRequest(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (!StringUtils.hasText(key) || CANONICAL_METADATA_KEYS.contains(key)) {
                return;
            }
            sanitized.put(key.trim(), value);
        });
        return sanitized.isEmpty() ? Map.of() : Map.copyOf(sanitized);
    }

    private String resolveEffectiveSessionId(AIAccessSubjectContext authContext) {
        if (StringUtils.hasText(authContext.getSessionId())) {
            return authContext.getSessionId().trim();
        }
        if ("ANONYMOUS_SESSION".equalsIgnoreCase(authContext.getSubjectType()) && StringUtils.hasText(authContext.getSubjectId())) {
            return authContext.getSubjectId().trim();
        }
        return null;
    }

    private String firstNonBlank(String primary, String secondary) {
        if (StringUtils.hasText(primary)) {
            return primary.trim();
        }
        if (StringUtils.hasText(secondary)) {
            return secondary.trim();
        }
        return null;
    }

    record RemoteAuthzCheckRequest(
        String contractVersion,
        String requestId,
        String userId,
        String subjectId,
        String subjectType,
        String authMode,
        String callerType,
        String sessionId,
        String deploymentId,
        String customerId,
        String tenantId,
        String issuer,
        List<String> grantedScopes,
        List<String> requestedScopes,
        String resourceId,
        String operationType,
        Map<String, Object> requestContext,
        Map<String, Object> metadata,
        Map<String, Object> userAttributes,
        Map<String, String> compatibilityAliases,
        RemoteAuthzVerifiedAuthContext authContext
    ) { }

    record RemoteAuthzVerifiedAuthContext(
        String subjectId,
        String subjectType,
        String authMode,
        String callerType,
        String sessionId,
        String deploymentId,
        String customerId,
        String tenantId,
        String issuer,
        List<String> grantedScopes,
        List<String> audiences,
        String expiresAt
    ) { }

    record RemoteAuthzCheckResponse(Boolean granted, String reason, String policyVersion) { }
}
