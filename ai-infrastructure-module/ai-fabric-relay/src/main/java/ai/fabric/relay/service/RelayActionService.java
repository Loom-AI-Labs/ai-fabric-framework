package ai.fabric.relay.service;

import ai.fabric.relay.api.ActionExecuteRequestDto;
import ai.fabric.relay.api.ActionResultDto;
import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.error.RelayRequestRejectedException;
import ai.fabric.relay.forward.ForwardingClient;
import ai.fabric.relay.forward.ForwardingClientException;
import ai.fabric.relay.forward.ForwardingResponse;
import ai.fabric.relay.ratelimit.FixedWindowRateLimiter;
import ai.fabric.relay.ratelimit.RateLimitedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class RelayActionService {

    private static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERROR_ACTION_NOT_SUPPORTED = "ACTION_NOT_SUPPORTED";
    private static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final String ERROR_TIMEOUT = "TIMEOUT";

    private final RelayProperties properties;
    private final ObjectMapper objectMapper;
    private final ForwardingClient forwardingClient;
    private final FixedWindowRateLimiter rateLimiter;
    private final IdempotencyStore idempotencyStore;
    private final AuditLogger auditLogger;

    public RelayActionService(RelayProperties properties,
                              ObjectMapper objectMapper,
                              ForwardingClient forwardingClient,
                              FixedWindowRateLimiter rateLimiter,
                              IdempotencyStore idempotencyStore,
                              AuditLogger auditLogger) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.forwardingClient = forwardingClient;
        this.rateLimiter = rateLimiter;
        this.idempotencyStore = idempotencyStore;
        this.auditLogger = auditLogger;
    }

    public ActionResultDto execute(ActionExecuteRequestDto request) {
        long start = System.currentTimeMillis();
        String actionId = request != null ? request.actionId() : null;
        if (!StringUtils.hasText(actionId)) {
            return ActionResultDto.failure(ERROR_INVALID_REQUEST, "actionId is required.");
        }

        String userKey = RelayTraceContextSupport.rateLimitKey(request != null ? request.trace() : null);

        try {
            rateLimiter.check(userKey, actionId.trim());
        } catch (RateLimitedException ex) {
            ActionResultDto out = ActionResultDto.failure("RATE_LIMITED", "Rate limited.", Map.of("retryAfterSeconds", ex.getRetryAfterSeconds()));
            auditLogger.logAction(actionId.trim(), request.trace(), false, "RATE_LIMITED", System.currentTimeMillis() - start);
            return out;
        }

        try {
            ActionResultDto result = idempotencyStore.executeIdempotent(request, () -> forward(request));
            auditLogger.logAction(actionId.trim(), request.trace(), result != null && result.success(), result != null ? result.errorCode() : ERROR_SERVICE_UNAVAILABLE, System.currentTimeMillis() - start);
            return result != null ? result : ActionResultDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal service returned no result.");
        } catch (RelayRequestRejectedException ex) {
            auditLogger.logAction(actionId.trim(), request.trace(), false, ex.getErrorCode(), System.currentTimeMillis() - start);
            throw ex;
        } catch (Exception ex) {
            auditLogger.logAction(actionId.trim(), request.trace(), false, ERROR_SERVICE_UNAVAILABLE, System.currentTimeMillis() - start);
            return ActionResultDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal service unavailable.");
        }
    }

    private ActionResultDto forward(ActionExecuteRequestDto request) {
        RelayProperties.Routing routing = properties != null ? properties.getRouting() : null;
        if (routing == null) {
            return ActionResultDto.failure(ERROR_SERVICE_UNAVAILABLE, "Relay routing is not configured.");
        }

        ResolvedRoute route = resolveRoute(routing, request.actionId());
        if (route == null) {
            return ActionResultDto.failure(ERROR_ACTION_NOT_SUPPORTED, "Action is not supported.");
        }

        String json = writeJson(request);
        Map<String, String> headers = RelayTraceContextSupport.forwardHeaders(request != null ? request.trace() : null);
        Duration timeout = Duration.ofMillis(Math.max(100, route.timeoutMs));

        ForwardingResponse response;
        try {
            response = forwardingClient.execute(URI.create(route.url), route.method, json, headers, timeout);
        } catch (ForwardingClientException ex) {
            String msg = ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("timeout")
                ? "Internal service timed out."
                : "Internal service unavailable.";
            return ActionResultDto.failure(ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("timeout") ? ERROR_TIMEOUT : ERROR_SERVICE_UNAVAILABLE, msg);
        }

        String body = response != null ? response.body() : null;
        if (!StringUtils.hasText(body)) {
            return ActionResultDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal service returned an empty response.");
        }

        try {
            return objectMapper.readValue(body, ActionResultDto.class);
        } catch (Exception ex) {
            return ActionResultDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal service returned invalid JSON.");
        }
    }

    private ResolvedRoute resolveRoute(RelayProperties.Routing routing, String actionId) {
        String id = actionId != null ? actionId.trim() : "";
        int defaultTimeoutMs = properties != null && properties.getLimits() != null
            ? properties.getLimits().getDefaultTimeoutMs()
            : 5000;

        if (routing.getMode() == RelayProperties.Routing.Mode.DISPATCHER) {
            RelayProperties.Dispatcher dispatcher = routing.getDispatcher();
            if (dispatcher == null || !StringUtils.hasText(dispatcher.getUrl())) {
                throw new RelayRequestRejectedException(HttpStatus.INTERNAL_SERVER_ERROR, ERROR_SERVICE_UNAVAILABLE,
                    "relay.routing.dispatcher.url is required when relay.routing.mode=DISPATCHER.");
            }
            return new ResolvedRoute(
                dispatcher.getUrl().trim(),
                "POST",
                dispatcher.getTimeoutMs() != null ? dispatcher.getTimeoutMs() : defaultTimeoutMs
            );
        }

        RelayProperties.Route route = routing.getActions() != null ? routing.getActions().get(id) : null;
        if (route == null || !StringUtils.hasText(route.getUrl())) {
            return null;
        }
        return new ResolvedRoute(
            route.getUrl().trim(),
            StringUtils.hasText(route.getMethod()) ? route.getMethod().trim().toUpperCase(Locale.ROOT) : "POST",
            route.getTimeoutMs() != null ? route.getTimeoutMs() : defaultTimeoutMs
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RelayRequestRejectedException(HttpStatus.BAD_REQUEST, ERROR_INVALID_REQUEST, "Invalid JSON.");
        }
    }

    private record ResolvedRoute(String url, String method, int timeoutMs) {
    }
}
