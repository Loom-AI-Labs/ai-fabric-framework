package com.ai.fabric.runtime.auth;

import java.util.List;

import lombok.Builder;
import lombok.Value;

@Value
@Builder(toBuilder = true)
public class RuntimeResolvedIdentity {
    RuntimeAuthContext authContext;
    List<String> warnings;

    public String ownerId() {
        return authContext != null ? authContext.ownerId() : null;
    }

    public String orchestrationUserId() {
        return authContext != null ? authContext.orchestrationUserId() : null;
    }

    public String orchestrationSessionId() {
        return authContext != null ? authContext.orchestrationSessionId() : null;
    }

    public boolean hasWarnings() {
        return warnings != null && !warnings.isEmpty();
    }
}
