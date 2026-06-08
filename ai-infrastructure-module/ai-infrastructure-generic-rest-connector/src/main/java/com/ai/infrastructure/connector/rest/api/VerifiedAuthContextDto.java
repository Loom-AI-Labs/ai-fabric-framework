package com.ai.infrastructure.connector.rest.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public record VerifiedAuthContextDto(
    String subjectId,
    String subjectType,
    String authMode,
    String callerType,
    String sessionId,
    String deploymentId,
    String customerId,
    String tenantId,
    String issuer,
    String expiresAt,
    List<String> grantedScopes,
    List<String> audiences
) {
}
