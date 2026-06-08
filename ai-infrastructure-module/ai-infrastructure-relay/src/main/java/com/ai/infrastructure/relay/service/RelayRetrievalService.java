package com.ai.infrastructure.relay.service;

import com.ai.infrastructure.relay.api.RetrievalSearchRequestDto;
import com.ai.infrastructure.relay.api.RetrievalSearchResponseDto;
import com.ai.infrastructure.relay.config.RelayProperties;
import com.ai.infrastructure.relay.forward.ForwardingClient;
import com.ai.infrastructure.relay.forward.ForwardingClientException;
import com.ai.infrastructure.relay.forward.ForwardingResponse;
import com.ai.infrastructure.relay.ratelimit.FixedWindowRateLimiter;
import com.ai.infrastructure.relay.ratelimit.RateLimitedException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

@Service
public class RelayRetrievalService {

    private static final String ERROR_INVALID_REQUEST = "INVALID_REQUEST";
    private static final String ERROR_RETRIEVAL_NOT_CONFIGURED = "RETRIEVAL_NOT_CONFIGURED";
    private static final String ERROR_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";
    private static final String ERROR_TIMEOUT = "TIMEOUT";

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

        String json = writeJson(request);
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
        if (!StringUtils.hasText(body)) {
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal retrieval service returned an empty response.");
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, out.errorCode(), System.currentTimeMillis() - start);
            return out;
        }

        try {
            RetrievalSearchResponseDto out = objectMapper.readValue(body, RetrievalSearchResponseDto.class);
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), out.success(), out.errorCode(), System.currentTimeMillis() - start);
            return out;
        } catch (Exception ex) {
            RetrievalSearchResponseDto out = RetrievalSearchResponseDto.failure(ERROR_SERVICE_UNAVAILABLE, "Internal retrieval service returned invalid JSON.");
            auditLogger.logRetrieval(request.vectorSpace().trim(), request.trace(), false, out.errorCode(), System.currentTimeMillis() - start);
            return out;
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "{}";
        }
    }

}
