package com.ai.fabric.realapps.incident.domain;

public record IncidentFailureView(
    String reason,
    String publicMessage,
    boolean retryable,
    String stepId
) {}
