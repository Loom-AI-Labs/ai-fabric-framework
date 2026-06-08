package com.ai.fabric.runtime.auth;

import lombok.Builder;
import lombok.Value;

import java.time.Instant;
import java.util.List;

@Value
@Builder(toBuilder = true)
public class RuntimeAuthContext {
    String subjectId;
    RuntimeAuthSubjectType subjectType;
    RuntimeAuthMode authMode;
    RuntimeAuthCallerType callerType;
    String deploymentId;
    String customerId;
    String tenantId;
    String sessionId;
    String issuer;
    @Builder.Default
    List<String> audiences = List.of();
    Instant expiresAt;
    @Builder.Default
    List<String> grantedScopes = List.of();

    public boolean isAnonymousSession() {
        return subjectType == RuntimeAuthSubjectType.ANONYMOUS_SESSION;
    }

    public String ownerId() {
        return subjectId;
    }

    public String orchestrationUserId() {
        return isAnonymousSession() ? null : subjectId;
    }

    public String orchestrationSessionId() {
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return isAnonymousSession() ? subjectId : null;
    }
}
