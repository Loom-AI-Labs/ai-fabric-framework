package com.ai.fabric.realapps.incident.domain;

import java.util.Objects;

public record IncidentRoutingScope(
    String incidentId,
    String deploymentId,
    String sourceRevision
) {
    public IncidentRoutingScope {
        incidentId = requireText(incidentId, "incidentId");
        deploymentId = requireText(deploymentId, "deploymentId");
        sourceRevision = requireText(sourceRevision, "sourceRevision");
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
