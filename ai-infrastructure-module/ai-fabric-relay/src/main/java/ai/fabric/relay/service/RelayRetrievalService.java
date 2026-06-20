package ai.fabric.relay.service;

import ai.fabric.relay.api.RetrievalSearchRequestDto;
import ai.fabric.relay.api.RetrievalSearchResponseDto;
import ai.fabric.relay.api.RetrievalDocumentDto;
import ai.fabric.relay.config.RelayProperties;
import ai.fabric.relay.error.RelayRequestRejectedException;
import ai.fabric.relay.forward.ForwardingClient;
import ai.fabric.relay.forward.ForwardingClientException;
import ai.fabric.relay.forward.ForwardingResponse;
import ai.fabric.relay.ratelimit.FixedWindowRateLimiter;
import ai.fabric.relay.ratelimit.RateLimitedException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RelayRetrievalService {

    private static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERROR_RETRIEVAL_NOT_CONFIGURED = "RETRIEVAL_NOT_CONFIGURED";
    private static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final String ERROR_TIMEOUT = "TIMEOUT";
    private static final String ERROR_RATE_LIMITED = "RATE_LIMITED";
    private static final String ERROR_HTTP_ERROR = "HTTP_ERROR";
    private static final String ERROR_INVALID_RESPONSE = "INVALID_RESPONSE";
    private static final Set<String> FORBIDDEN_DOCUMENTS_ONLY_RESPONSE_KEYS = Set.of(
        "answer",
        "generatedanswer",
        "finalanswer",
        "response",
        "completion",
        "toolinstructions",
        "toolcalls",
        "tools",
        "prompt",
        "systemprompt",
        "hiddenprompt",
        "messages",
        "instructions"
    );

    private final RelayProperties properties;
    private final ObjectMapper objectMapper;
    private final ForwardingClient forwardingClient;
    private final FixedWindowRateLimiter rateLimiter;
    private final AuditLogger auditLogger;

    public RelayRetrievalService(RelayProperties properties,
                                 ObjectMapper objectMapper,
                                 ForwardingClient forwardingClient,
                                 FixedWindowRateLimiter rateLimiter,
                                 AuditLogger auditLogger) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.forwardingClient = forwardingClient;
        this.rateLimiter = rateLimiter;
        this.auditLogger = auditLogger;
    }

    public RetrievalSearchResponseDto search(RetrievalSearchRequestDto request) {
        long start = System.currentTimeMillis();
        if (request == null || !StringUtils.hasText(request.query()) || !StringUtils.hasText(request.vectorSpace()) || request.trace() == null) {
            return RetrievalSearchResponseDto.failure(ERROR_INVALID_REQUEST, "query, vectorSpace, and trace are required.");
        }

        String userKey = RelayTraceContextSupport.rateLimitKey(request.trace());
        String actionKey = "retrieval_search";
        try {
            rateLimiter.check(userKey, actionKey);
        } catch (RateLimitedException ex) {
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure("RATE_LIMITED", "Rate limited.");
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, "RATE_LIMITED", System.currentTimeMillis() - start);
            return out;
        }

        RelayProperties.Routing routing = properties != null ? properties.getRouting() : null;
        RelayProperties.Retrieval retrieval = routing != null ? routing.getRetrieval() : null;
        if (retrieval == null || !StringUtils.hasText(retrieval.getUrl())) {
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure(ERROR_RETRIEVAL_NOT_CONFIGURED, "Retrieval is not configured.");
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, ERROR_RETRIEVAL_NOT_CONFIGURED, System.currentTimeMillis() - start);
            return out;
        }

        int defaultTimeoutMs = properties != null && properties.getLimits() != null
            ? properties.getLimits().getDefaultTimeoutMs()
            : 5000;
        int timeoutMs = retrieval.getTimeoutMs() != null ? retrieval.getTimeoutMs() : defaultTimeoutMs;

        String json;
        try {
            json = writeJson(request);
        } catch (RelayRequestRejectedException ex) {
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, ex.getErrorCode(), System.currentTimeMillis() - start);
            throw ex;
        }
        Map<String, String> headers = RelayTraceContextSupport.forwardHeaders(request.trace());
        ForwardingResponse response;
        try {
            response = forwardingClient.execute(URI.create(retrieval.getUrl().trim()), "POST", json, headers, Duration.ofMillis(Math.max(100, timeoutMs)));
        } catch (ForwardingClientException ex) {
            String msg = ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("timeout")
                ? "Internal retrieval service timed out."
                : "Internal retrieval service unavailable.";
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure(ex.getMessage() != null && ex.getMessage().toLowerCase(Locale.ROOT).contains("timeout") ? ERROR_TIMEOUT : ERROR_SERVICE_UNAVAILABLE, msg);
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, out.errorCode(), System.currentTimeMillis() - start);
            return out;
        }

        String body = response != null ? response.body() : null;
        int statusCode = response != null ? response.statusCode() : 0;
        if (statusCode < 200 || statusCode >= 300) {
            RetrievalSearchResponseDto out = failureForHttpStatus(statusCode, body);
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, out.errorCode(), System.currentTimeMillis() - start);
            return out;
        }

        if (!StringUtils.hasText(body)) {
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal retrieval service returned an empty response.");
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, out.errorCode(), System.currentTimeMillis() - start);
            return out;
        }

        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(body, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal retrieval service returned invalid JSON.");
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, out.errorCode(), System.currentTimeMillis() - start);
            return out;
        }

        RetrievalSearchResponseDto invalid = validateRawSuccessResponse(parsed);
        if (invalid != null) {
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, invalid.errorCode(), System.currentTimeMillis() - start);
            return invalid;
        }

        try {
            RetrievalSearchResponseDto out = objectMapper.convertValue(parsed, RetrievalSearchResponseDto.class);
            out = sanitizeSuccessfulDocuments(out);
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), out.success(), out.errorCode(), System.currentTimeMillis() - start);
            return out;
        } catch (Exception ex) {
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure(ERROR_INVALID_RESPONSE, "Internal retrieval service returned an invalid retrieval response.");
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, out.errorCode(), System.currentTimeMillis() - start);
            return out;
        }
    }

    private RetrievalSearchResponseDto failureForHttpStatus(int statusCode, String body) {
        if (StringUtils.hasText(body)) {
            try {
                RetrievalSearchResponseDto parsed = objectMapper.readValue(body, RetrievalSearchResponseDto.class);
                if (parsed != null && !parsed.success()) {
                    String code = StringUtils.hasText(parsed.errorCode()) ? parsed.errorCode() : errorCodeForStatus(statusCode);
                    String message = StringUtils.hasText(parsed.message())
                        ? parsed.message()
                        : "Internal retrieval service returned HTTP " + statusCode + ".";
                    return RetrievalSearchResponseDto.failure(code, message);
                }
            } catch (Exception ignored) {
                // Fall through to deterministic status mapping.
            }
        }
        return RetrievalSearchResponseDto.failure(
            errorCodeForStatus(statusCode),
            "Internal retrieval service returned HTTP " + statusCode + "."
        );
    }

    private String errorCodeForStatus(int statusCode) {
        if (statusCode == 408) {
            return ERROR_TIMEOUT;
        }
        if (statusCode == 429) {
            return ERROR_RATE_LIMITED;
        }
        if (statusCode >= 500 || statusCode <= 0) {
            return ERROR_SERVICE_UNAVAILABLE;
        }
        return ERROR_HTTP_ERROR;
    }

    private RetrievalSearchResponseDto validateRawSuccessResponse(Map<String, Object> parsed) {
        if (parsed == null || !readBoolean(parsed.get("success"), false)) {
            return null;
        }

        List<String> forbiddenKeys = forbiddenDocumentsOnlyResponseKeys(parsed);
        if (!forbiddenKeys.isEmpty()) {
            return RetrievalSearchResponseDto.failure(
                ERROR_INVALID_RESPONSE,
                "Internal retrieval response must be documents-only. Forbidden top-level field(s): "
                    + String.join(", ", forbiddenKeys) + "."
            );
        }

        Object documents = parsed.get("documents");
        if (!(documents instanceof List<?>)) {
            return RetrievalSearchResponseDto.failure(
                ERROR_INVALID_RESPONSE,
                "Internal retrieval response documents must be an array."
            );
        }
        return null;
    }

    private List<String> forbiddenDocumentsOnlyResponseKeys(Map<String, Object> parsed) {
        if (parsed == null || parsed.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String key : parsed.keySet()) {
            if (!StringUtils.hasText(key)) {
                continue;
            }
            String normalized = key.trim().replace("_", "").replace("-", "").toLowerCase(Locale.ROOT);
            if (FORBIDDEN_DOCUMENTS_ONLY_RESPONSE_KEYS.contains(normalized)) {
                out.add(key.trim());
            }
        }
        return List.copyOf(out);
    }

    private RetrievalSearchResponseDto sanitizeSuccessfulDocuments(RetrievalSearchResponseDto response) {
        if (response == null || !response.success()) {
            return response;
        }
        List<RetrievalDocumentDto> documents = response.documents();
        if (documents == null) {
            return RetrievalSearchResponseDto.failure(
                ERROR_INVALID_RESPONSE,
                "Internal retrieval response documents must be an array."
            );
        }
        List<RetrievalDocumentDto> valid = documents.stream()
            .filter(this::isValidDocument)
            .toList();
        if (!documents.isEmpty() && valid.isEmpty()) {
            return RetrievalSearchResponseDto.failure(
                ERROR_INVALID_RESPONSE,
                "Internal retrieval response did not include any valid documents."
            );
        }
        if (valid.size() == documents.size()) {
            return response;
        }
        return new RetrievalSearchResponseDto(
            true,
            response.message(),
            null,
            valid,
            valid.size(),
            response.totalCount(),
            response.cursor()
        );
    }

    private boolean isValidDocument(RetrievalDocumentDto document) {
        return document != null
            && StringUtils.hasText(document.id())
            && StringUtils.hasText(document.content())
            && document.score() != null;
    }

    private boolean readBoolean(Object raw, boolean defaultValue) {
        if (raw instanceof Boolean b) {
            return b;
        }
        if (raw instanceof String s && StringUtils.hasText(s)) {
            return Boolean.parseBoolean(s.trim());
        }
        return defaultValue;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new RelayRequestRejectedException(HttpStatus.BAD_REQUEST, ERROR_INVALID_REQUEST, "Invalid JSON.");
        }
    }

}
