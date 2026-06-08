package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Canonical subject context used by access-control hooks.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIAccessSubjectContext {

    private String subjectId;
    private String sessionId;
    private String subjectType;
    private String authMode;
    private String callerType;
    private String deploymentId;
    private String customerId;
    private String tenantId;
    private String issuer;
    private List<String> grantedScopes;
    private List<String> audiences;
    private String expiresAt;
}
