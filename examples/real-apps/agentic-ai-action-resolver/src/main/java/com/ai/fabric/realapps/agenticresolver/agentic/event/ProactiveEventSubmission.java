package com.ai.fabric.realapps.agenticresolver.agentic.event;

import ai.fabric.execution.gateway.ExecutionHandle;
import java.util.Objects;

public record ProactiveEventSubmission(
    String eventId,
    String eventType,
    ExecutionHandle execution
) {
    public ProactiveEventSubmission {
        eventId = requireText(eventId, "eventId");
        eventType = requireText(eventType, "eventType");
        Objects.requireNonNull(execution, "execution is required");
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
