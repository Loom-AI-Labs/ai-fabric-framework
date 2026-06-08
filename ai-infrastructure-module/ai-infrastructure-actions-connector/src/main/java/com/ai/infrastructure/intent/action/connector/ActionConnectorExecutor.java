package com.ai.infrastructure.intent.action.connector;

import com.ai.infrastructure.dto.AIAccessSubjectContext;
import com.ai.infrastructure.http.OutboundHttpExecutionRequest;
import com.ai.infrastructure.http.OutboundHttpExecutionResponse;
import com.ai.infrastructure.http.OutboundHttpExecutor;
import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionListPayload;
import com.ai.infrastructure.intent.action.ActionObjectPayload;
import com.ai.infrastructure.intent.action.ActionPayload;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionResultContracts;
import com.ai.infrastructure.intent.action.ActionTargetRef;
import com.ai.infrastructure.intent.orchestration.attachment.OrchestrationAttachment;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.util.UlidGenerator;

/**
 * Executes connector-backed actions by calling the Customer Connector API.
 *
 * <p>Greenfield: the connector boundary is strict and deterministic. Transport-level failures return a stable
 * error {@link ActionResult} and retries are bounded and only performed when safe.</p>
 */
@Slf4j
@Service
public class ActionConnectorExecutor {

    private static final String IDEMPOTENCY_PREFIX = "act_";

    private static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final String ERROR_TIMEOUT = "TIMEOUT";
    private static final String ERROR_RATE_LIMITED = "RATE_LIMITED";
    private static final String ERROR_INVALID_CONFIGURATION = "INVALID_CONFIGURATION";

    private static final Set<String> RETRYABLE_ERROR_CODES = Set.of(
        ERROR_TIMEOUT,
        ERROR_SERVICE_UNAVAILABLE,
        ERROR_RATE_LIMITED
    );
    private static final Pattern MCP_SECRET_REF = Pattern.compile("^MCP_SECRET_[A-Z0-9_]+$");

    private final AIActionConnectorProperties properties;
    private final OutboundHttpExecutor outboundHttpExecutor;
    private final ObjectMapper objectMapper;
    private final UlidGenerator ulidGenerator;
    private final Clock clock;

    public ActionConnectorExecutor(AIActionConnectorProperties properties,
                                   OutboundHttpExecutor outboundHttpExecutor,
                                   ObjectProvider<ObjectMapper> objectMapperProvider,
                                   Clock clock) {
        this.properties = properties;
        this.outboundHttpExecutor = outboundHttpExecutor;
        this.objectMapper = objectMapperProvider != null
            ? objectMapperProvider.getIfAvailable(ObjectMapper::new)
            : new ObjectMapper();
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.ulidGenerator = new UlidGenerator(this.clock);
    }

    /**
     * Execute an action via the connector.
     *
     * @param actionId framework action identifier
     * @param accessMode side-effect semantics (required)
     * @param params action parameters (nullable)
     * @param context action context (nullable)
     * @return action result (never null)
     */
    public ActionResult execute(String actionId,
                                ActionAccessMode accessMode,
                                Map<String, Object> params,
                                ActionContext context) {
        return execute(actionId, accessMode, params, context, null);
    }

    public ActionResult execute(String actionId,
                                ActionAccessMode accessMode,
                                Map<String, Object> params,
                                ActionContext context,
                                Map<String, Object> actionConfig) {
        if (!StringUtils.hasText(actionId)) {
            throw new IllegalArgumentException("actionId is required");
        }
        if (accessMode == null) {
            throw new IllegalArgumentException("accessMode is required");
        }

        boolean mcpToolAction = isMcpToolAction(actionConfig);
        String url;
        try {
            url = mcpToolAction ? buildMcpGatewayExecuteUrl() : buildExecuteUrl();
        } catch (Exception ex) {
            String message = safeConfigErrorMessage(ex != null ? ex.getMessage() : null);
            log.warn("Connector action '{}' skipped due to invalid {} configuration: {}",
                actionId,
                mcpToolAction ? "MCP gateway" : "connector",
                message);
            return failure(ERROR_INVALID_CONFIGURATION, message);
        }
        String idempotencyKey = needsIdempotency(accessMode) ? generateIdempotencyKey() : null;

        Map<String, Object> request = new LinkedHashMap<>();
        request.put(ActionConnectorProtocol.KEY_ACTION_ID, actionId.trim());
        request.put(ActionConnectorProtocol.KEY_PARAMS, params != null ? new LinkedHashMap<>(params) : Map.of());
        if (StringUtils.hasText(idempotencyKey)) {
            request.put(ActionConnectorProtocol.KEY_IDEMPOTENCY_KEY, idempotencyKey);
        }
        request.put(ActionConnectorProtocol.KEY_TRACE, buildTrace(context, actionConfig));

        String body = writeJson(request);
        HttpHeaders headers;
        try {
            headers = mcpToolAction ? buildMcpGatewayHeaders() : buildHeaders(body);
        } catch (Exception ex) {
            String message = safeConfigErrorMessage(ex != null ? ex.getMessage() : null);
            log.warn("Connector action '{}' skipped due to invalid {} configuration: {}",
                actionId,
                mcpToolAction ? "MCP gateway" : "connector",
                message);
            return failure(ERROR_INVALID_CONFIGURATION, message);
        }
        Map<String, String> headerValues = new LinkedHashMap<>();
        headers.forEach((key, values) -> {
            if (StringUtils.hasText(key) && values != null && !values.isEmpty()) {
                headerValues.put(key, values.getFirst());
            }
        });

        int maxAttempts = Math.max(1, properties != null ? properties.getMaxAttempts() : 1);
        Duration backoff = properties != null && properties.getInitialBackoff() != null
            ? properties.getInitialBackoff()
            : Duration.ofSeconds(1);
        Duration connectTimeout = properties != null ? properties.getConnectTimeout() : null;
        Duration readTimeout = properties != null ? properties.getReadTimeout() : null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                OutboundHttpExecutionResponse response = outboundHttpExecutor.execute(
                    new OutboundHttpExecutionRequest(
                        url,
                        HttpMethod.POST,
                        headerValues,
                        body,
                        connectTimeout,
                        readTimeout
                    )
                );
                ActionResult result = parseResponse(response, actionId);

                if (shouldRetryResult(result, accessMode, idempotencyKey) && attempt < maxAttempts) {
                    sleep(backoffForAttempt(backoff, attempt));
                    continue;
                }

                return result != null ? result : failure(ERROR_SERVICE_UNAVAILABLE, "Connector returned null result.");
            } catch (Exception ex) {
                if (ex instanceof IllegalArgumentException) {
                    String message = safeConfigErrorMessage(ex.getMessage());
                    log.warn("Connector action '{}' skipped due to invalid connector configuration: {}", actionId, message);
                    return failure(ERROR_INVALID_CONFIGURATION, message);
                }
                if (shouldRetryException(ex, accessMode, idempotencyKey) && attempt < maxAttempts) {
                    sleep(backoffForAttempt(backoff, attempt));
                    continue;
                }
                log.warn("Connector action '{}' failed (attempt {}/{}): {}", actionId, attempt, maxAttempts, ex.getMessage());
                return failure(ERROR_SERVICE_UNAVAILABLE, "Connector service is unavailable.");
            }
        }

        return failure(ERROR_SERVICE_UNAVAILABLE, "Connector service is unavailable.");
    }

    private boolean needsIdempotency(ActionAccessMode accessMode) {
        return accessMode == ActionAccessMode.WRITE_ONLY || accessMode == ActionAccessMode.READ_WRITE;
    }

    private String generateIdempotencyKey() {
        return IDEMPOTENCY_PREFIX + ulidGenerator.nextUlid();
    }

    private Map<String, Object> buildTrace(ActionContext context, Map<String, Object> actionConfig) {
        Map<String, Object> trace = new LinkedHashMap<>();
        if (actionConfig != null && !actionConfig.isEmpty()) {
            trace.put("actionConfig", new LinkedHashMap<>(actionConfig));
            Map<String, String> secretValues = resolveMcpSecretValues(actionConfig);
            if (!secretValues.isEmpty()) {
                trace.put("mcpSecretValues", secretValues);
            }
        }
        if (context == null) {
            return trace;
        }
        putIfText(trace, ActionConnectorProtocol.TRACE_REQUEST_ID, context.requestId());
        putIfText(trace, ActionConnectorProtocol.TRACE_CONVERSATION_ID, context.conversationId());
        putIfText(trace, ActionConnectorProtocol.TRACE_USER_ID, context.userId());
        putIfText(trace, ActionConnectorProtocol.TRACE_SESSION_ID, context.sessionId());
        Map<String, Object> authContext = buildAuthContextTrace(context.authContext());
        if (!authContext.isEmpty()) {
            trace.put(ActionConnectorProtocol.TRACE_AUTH_CONTEXT, authContext);
        }
        putIfText(trace, "shopDomain", resolveShopDomainTraceValue(context));
        return trace;
    }

    private Map<String, String> resolveMcpSecretValues(Map<String, Object> actionConfig) {
        Set<String> refs = collectMcpSecretRefs(actionConfig);
        if (refs.isEmpty()) {
            return Map.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String ref : refs) {
            String value = firstNonBlank(System.getenv(ref), System.getProperty(ref));
            if (StringUtils.hasText(value)) {
                values.put(ref, value.trim());
            }
        }
        return values.isEmpty() ? Map.of() : Map.copyOf(values);
    }

    private Set<String> collectMcpSecretRefs(Object value) {
        Set<String> refs = new java.util.LinkedHashSet<>();
        collectMcpSecretRefs(value, refs);
        return refs;
    }

    private void collectMcpSecretRefs(Object value, Set<String> refs) {
        if (value == null) {
            return;
        }
        if (value instanceof CharSequence text) {
            String candidate = text.toString().trim();
            if (MCP_SECRET_REF.matcher(candidate).matches()) {
                refs.add(candidate);
            }
            return;
        }
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(item -> collectMcpSecretRefs(item, refs));
            return;
        }
        if (value instanceof Iterable<?> iterable) {
            iterable.forEach(item -> collectMcpSecretRefs(item, refs));
        }
    }

    private String firstNonBlank(String first, String second) {
        if (StringUtils.hasText(first)) {
            return first;
        }
        return StringUtils.hasText(second) ? second : null;
    }

    private Map<String, Object> buildAuthContextTrace(AIAccessSubjectContext authContext) {
        if (authContext == null) {
            return Map.of();
        }
        Map<String, Object> trace = new LinkedHashMap<>();
        putIfText(trace, "subjectId", authContext.getSubjectId());
        putIfText(trace, "subjectType", authContext.getSubjectType());
        putIfText(trace, "authMode", authContext.getAuthMode());
        putIfText(trace, "callerType", authContext.getCallerType());
        putIfText(trace, "sessionId", authContext.getSessionId());
        putIfText(trace, "deploymentId", authContext.getDeploymentId());
        putIfText(trace, "customerId", authContext.getCustomerId());
        putIfText(trace, "tenantId", authContext.getTenantId());
        putIfText(trace, "issuer", authContext.getIssuer());
        putIfText(trace, "expiresAt", authContext.getExpiresAt());
        if (authContext.getGrantedScopes() != null && !authContext.getGrantedScopes().isEmpty()) {
            trace.put("grantedScopes", authContext.getGrantedScopes());
        }
        if (authContext.getAudiences() != null && !authContext.getAudiences().isEmpty()) {
            trace.put("audiences", authContext.getAudiences());
        }
        return trace.isEmpty() ? Map.of() : Map.copyOf(trace);
    }

    private void putIfText(Map<String, Object> out, String key, String value) {
        if (out == null || !StringUtils.hasText(key) || !StringUtils.hasText(value)) {
            return;
        }
        out.put(key, value.trim());
    }

    private String resolveShopDomainTraceValue(ActionContext context) {
        if (context == null) {
            return null;
        }
        String value = firstText(context.metadata(), "shopDomain", "shop_domain", "storeDomain", "store_domain");
        if (StringUtils.hasText(value)) {
            return value;
        }
        if (context.orchestrationContext() == null || context.orchestrationContext().getAttachments() == null) {
            return null;
        }
        for (OrchestrationAttachment attachment : context.orchestrationContext().getAttachments()) {
            if (attachment == null) {
                continue;
            }
            value = firstText(attachment.getMetadata(), "shopDomain", "shop_domain", "storeDomain", "store_domain");
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Map<String, ?> source, String... keys) {
        if (source == null || source.isEmpty() || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            Object value = source.get(key);
            if (value != null && StringUtils.hasText(value.toString())) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private String buildExecuteUrl() {
        String baseUrl = properties != null ? properties.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("ai.actions.connector.baseUrl is required to execute connector actions.");
        }
        String base = baseUrl.trim();
        String baseLower = base.toLowerCase(Locale.ROOT);
        if (!(baseLower.startsWith("http://") || baseLower.startsWith("https://"))) {
            throw new IllegalStateException("ai.actions.connector.baseUrl must be an absolute URL starting with http:// or https:// (example: https://<connector>.railway.app).");
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String path = properties != null ? properties.getExecutePath() : null;
        if (!StringUtils.hasText(path)) {
            path = ActionConnectorProtocol.PATH_DEFAULT_EXECUTE;
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }

        return base + p;
    }

    private String buildMcpGatewayExecuteUrl() {
        AIActionConnectorProperties.McpGatewayProperties gateway = properties != null ? properties.getMcpGateway() : null;
        String baseUrl = gateway != null ? gateway.getBaseUrl() : null;
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("ai.actions.connector.mcp-gateway.base-url is required to execute mcp-tool actions.");
        }
        String base = baseUrl.trim();
        String baseLower = base.toLowerCase(Locale.ROOT);
        if (!(baseLower.startsWith("http://") || baseLower.startsWith("https://"))) {
            throw new IllegalStateException("ai.actions.connector.mcp-gateway.base-url must be an absolute URL starting with http:// or https://.");
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }

        String path = gateway != null ? gateway.getExecutePath() : null;
        if (!StringUtils.hasText(path)) {
            path = "/api/internal/mcp/actions/execute";
        }
        String p = path.trim();
        if (!p.startsWith("/")) {
            p = "/" + p;
        }
        return base + p;
    }

    private String safeConfigErrorMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return "Invalid connector configuration.";
        }
        String normalized = message.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.contains("uri is not absolute")) {
            return "Invalid ai.actions.connector.baseUrl: it must include http:// or https:// (example: https://<connector>.railway.app).";
        }
        return normalized;
    }

    private HttpHeaders buildHeaders(String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        AIActionConnectorProperties.ApiKeyProperties apiKey = properties != null ? properties.getApiKey() : null;
        if (apiKey != null && StringUtils.hasText(apiKey.getHeader()) && StringUtils.hasText(apiKey.getValue())) {
            headers.set(apiKey.getHeader().trim(), apiKey.getValue().trim());
        }

        AIActionConnectorProperties.HmacProperties hmac = properties != null ? properties.getHmac() : null;
        if (hmac != null && StringUtils.hasText(hmac.getSecret())) {
            String timestamp = String.valueOf(Instant.now(clock).getEpochSecond());
            String nonce = ulidGenerator.nextUlid();
            String signature = sign(hmac.getSecret(), timestamp, nonce, body);
            headers.set(hmac.getTimestampHeader(), timestamp);
            headers.set(hmac.getNonceHeader(), nonce);
            headers.set(hmac.getSignatureHeader(), signature);
        }

        return headers;
    }

    private HttpHeaders buildMcpGatewayHeaders() {
        AIActionConnectorProperties.McpGatewayProperties gateway = properties != null ? properties.getMcpGateway() : null;
        if (gateway == null || !StringUtils.hasText(gateway.getApiKey())) {
            throw new IllegalStateException("ai.actions.connector.mcp-gateway.api-key is required to execute mcp-tool actions.");
        }
        String header = StringUtils.hasText(gateway.getApiKeyHeader())
            ? gateway.getApiKeyHeader().trim()
            : "X-MCP-GATEWAY-API-KEY";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set(header, gateway.getApiKey().trim());
        return headers;
    }

    private boolean isMcpToolAction(Map<String, Object> actionConfig) {
        if (actionConfig == null || actionConfig.isEmpty()) {
            return false;
        }
        Object adapterType = actionConfig.get("adapterType");
        if (adapterType != null && "mcp-tool".equalsIgnoreCase(adapterType.toString().trim())) {
            return true;
        }
        Object executionRaw = actionConfig.get("execution");
        if (executionRaw instanceof Map<?, ?> execution) {
            Object executionAdapterType = execution.get("adapterType");
            if (executionAdapterType != null && "mcp-tool".equalsIgnoreCase(executionAdapterType.toString().trim())) {
                return true;
            }
            return execution.containsKey("mcp");
        }
        return false;
    }

    private String sign(String secret, String timestamp, String nonce, String body) {
        try {
            String message = timestamp + "\n" + nonce + "\n" + (body != null ? body : "");
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compute HMAC signature: " + ex.getMessage(), ex);
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize connector request JSON: " + ex.getMessage(), ex);
        }
    }

    private ActionResult parseResponse(OutboundHttpExecutionResponse response, String actionId) {
        if (response == null) {
            return failure(ERROR_SERVICE_UNAVAILABLE, "Connector returned no response.");
        }

        String body = response.body();
        if (!StringUtils.hasText(body)) {
            return failure(ERROR_SERVICE_UNAVAILABLE, "Connector returned an empty response.");
        }

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return failure(ERROR_SERVICE_UNAVAILABLE, "Connector returned invalid JSON.");
        }

        boolean success = readBoolean(parsed.get(ActionConnectorProtocol.KEY_SUCCESS), false);
        String message = readString(parsed.get(ActionConnectorProtocol.KEY_MESSAGE));
        String errorCode = readString(parsed.get(ActionConnectorProtocol.KEY_ERROR_CODE));
        Object dataRaw = parsed.get(ActionConnectorProtocol.KEY_DATA);
        Object pinnedRaw = parsed.get(ActionConnectorProtocol.KEY_PINNED_TARGETS);

        ActionPayload payload = null;
        if (dataRaw != null) {
            payload = toActionPayload(dataRaw, actionId);
        }

        List<ActionTargetRef> pinnedTargets = toPinnedTargets(pinnedRaw);

        return ActionResult.builder()
            .success(success)
            .message(message)
            .data(payload)
            .pinnedTargets(pinnedTargets)
            .errorCode(errorCode)
            .build();
    }

    private ActionPayload toActionPayload(Object raw, String actionId) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalStateException("Connector action '" + actionId + "' returned invalid data payload (expected object).");
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            fields.put(entry.getKey().toString(), entry.getValue());
        }

        if (fields.containsKey(ActionResultContracts.LIST_ITEMS)) {
            return toListPayload(fields, actionId);
        }

        // Object payload: reserved list keys are forbidden.
        return ActionObjectPayload.of(fields);
    }

    @SuppressWarnings("unchecked")
    private ActionPayload toListPayload(Map<String, Object> fields, String actionId) {
        Object itemsRaw = fields.get(ActionResultContracts.LIST_ITEMS);
        Object countRaw = fields.get(ActionResultContracts.LIST_COUNT);

        if (!(itemsRaw instanceof List<?> items)) {
            throw new IllegalStateException("Connector action '" + actionId + "': _items must be an array.");
        }
        if (!(countRaw instanceof Number number)) {
            throw new IllegalStateException("Connector action '" + actionId + "': _count must be a number.");
        }
        int count = number.intValue();
        if (count != items.size()) {
            throw new IllegalStateException("Connector action '" + actionId + "': _count must equal _items.length.");
        }

        Long totalCount = null;
        Object totalRaw = fields.get(ActionResultContracts.LIST_TOTAL_COUNT);
        if (totalRaw instanceof Number n) {
            totalCount = n.longValue();
        }

        String cursor = null;
        Object cursorRaw = fields.get(ActionResultContracts.LIST_CURSOR);
        if (cursorRaw != null && StringUtils.hasText(cursorRaw.toString())) {
            cursor = cursorRaw.toString();
        }

        Map<String, Object> extra = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            String key = entry.getKey();
            if (!StringUtils.hasText(key) || ActionResultContracts.isReservedListKey(key)) {
                continue;
            }
            extra.put(key, entry.getValue());
        }

        return ActionListPayload.of(items, count, totalCount, cursor, extra);
    }

    private List<ActionTargetRef> toPinnedTargets(Object raw) {
        if (raw == null) {
            return List.of();
        }
        try {
            if (raw instanceof List<?> list) {
                List<ActionTargetRef> out = new ArrayList<>();
                for (Object item : list) {
                    if (item == null) {
                        continue;
                    }
                    ActionTargetRef ref = objectMapper.convertValue(item, ActionTargetRef.class);
                    if (ref != null) {
                        out.add(ref);
                    }
                }
                return Collections.unmodifiableList(out);
            }
        } catch (Exception ex) {
            log.debug("Failed to parse pinnedTargets: {}", ex.getMessage());
        }
        return List.of();
    }

    private boolean shouldRetryResult(ActionResult result, ActionAccessMode accessMode, String idempotencyKey) {
        if (result == null) {
            return true;
        }
        if (result.isSuccess()) {
            return false;
        }
        if (!StringUtils.hasText(result.getErrorCode())) {
            return false;
        }
        String code = result.getErrorCode().trim().toUpperCase(Locale.ROOT);
        if (!RETRYABLE_ERROR_CODES.contains(code)) {
            return false;
        }
        return accessMode == ActionAccessMode.READ || StringUtils.hasText(idempotencyKey);
    }

    private boolean shouldRetryException(Exception ex, ActionAccessMode accessMode, String idempotencyKey) {
        if (ex == null) {
            return false;
        }
        // Contract/schema violations are not transient. Do not retry them.
        if (ex instanceof IllegalArgumentException || ex instanceof IllegalStateException) {
            return false;
        }
        return accessMode == ActionAccessMode.READ || StringUtils.hasText(idempotencyKey);
    }

    private Duration backoffForAttempt(Duration initial, int attempt) {
        Duration base = initial != null ? initial : Duration.ofSeconds(1);
        long multiplier = Math.max(1, 1L << Math.min(8, Math.max(0, attempt - 1)));
        long millis = base.toMillis() * multiplier;
        return Duration.ofMillis(Math.min(millis, Duration.ofSeconds(8).toMillis()));
    }

    private void sleep(Duration d) {
        if (d == null || d.isZero() || d.isNegative()) {
            return;
        }
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private ActionResult failure(String errorCode, String message) {
        return ActionResult.builder()
            .success(false)
            .message(message)
            .errorCode(errorCode)
            .build();
    }

    private boolean readBoolean(Object raw, boolean defaultValue) {
        if (raw == null) {
            return defaultValue;
        }
        if (raw instanceof Boolean b) {
            return b;
        }
        String s = raw.toString();
        return StringUtils.hasText(s) ? Boolean.parseBoolean(s.trim()) : defaultValue;
    }

    private String readString(Object raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.toString();
        return StringUtils.hasText(s) ? s.trim() : null;
    }
}
