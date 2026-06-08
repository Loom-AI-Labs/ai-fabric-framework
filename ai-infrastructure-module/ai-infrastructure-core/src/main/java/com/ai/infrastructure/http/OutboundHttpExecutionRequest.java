package com.ai.infrastructure.http;

import org.springframework.http.HttpMethod;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public record OutboundHttpExecutionRequest(
    String url,
    HttpMethod method,
    Map<String, String> headers,
    String body,
    Duration connectTimeout,
    Duration readTimeout
) {
    public OutboundHttpExecutionRequest {
        headers = headers != null ? Map.copyOf(new LinkedHashMap<>(headers)) : Map.of();
    }
}
