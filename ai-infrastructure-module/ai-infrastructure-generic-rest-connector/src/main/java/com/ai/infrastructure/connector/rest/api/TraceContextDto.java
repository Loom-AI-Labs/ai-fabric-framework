package com.ai.infrastructure.connector.rest.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record TraceContextDto(
    String requestId,
    String conversationId,
    VerifiedAuthContextDto authContext
) {
}
