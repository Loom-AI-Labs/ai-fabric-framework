package com.ai.infrastructure.connector.rest.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record ActionExecuteRequestDto(
    String actionId,
    Map<String, Object> params,
    String idempotencyKey,
    TraceContextDto trace
) {
}

