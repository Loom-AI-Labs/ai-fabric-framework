package com.ai.infrastructure.relay.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record ActionResultDto(
    boolean success,
    String message,
    Map<String, Object> data,
    List<ActionTargetRefDto> pinnedTargets,
    String errorCode
) {
    public static ActionResultDto ok(String message, Map<String, Object> data) {
        return new ActionResultDto(true, message, data, null, null);
    }

    public static ActionResultDto failure(String errorCode, String message) {
        return new ActionResultDto(false, message, null, null, errorCode);
    }

    public static ActionResultDto failure(String errorCode, String message, Map<String, Object> data) {
        return new ActionResultDto(false, message, data, null, errorCode);
    }
}

