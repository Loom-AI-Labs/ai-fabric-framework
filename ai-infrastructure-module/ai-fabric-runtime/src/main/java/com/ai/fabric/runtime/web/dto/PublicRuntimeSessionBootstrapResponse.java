package com.ai.fabric.runtime.web.dto;

import java.util.List;

public record PublicRuntimeSessionBootstrapResponse(
    boolean success,
    String tokenType,
    String token,
    String authMode,
    String subjectType,
    String sessionId,
    String deploymentId,
    String customerId,
    String tenantId,
    List<String> grantedScopes,
    List<String> audiences,
    String expiresAt,
    RuntimeShellConfigResponse shellConfig
) {
}
