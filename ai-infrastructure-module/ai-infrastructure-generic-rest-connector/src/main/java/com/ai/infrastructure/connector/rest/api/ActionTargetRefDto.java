package com.ai.infrastructure.connector.rest.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record ActionTargetRefDto(
    String id,
    String vectorSpace,
    String contentText,
    Map<String, String> metadata
) {
}

