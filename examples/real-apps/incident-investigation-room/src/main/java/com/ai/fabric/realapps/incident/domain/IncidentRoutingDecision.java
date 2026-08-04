package com.ai.fabric.realapps.incident.domain;

public record IncidentRoutingDecision(
    Decision decision,
    String targetSpecialist,
    String reason
) {
    public enum Decision {
        ROUTE,
        COMPLETE
    }
}
