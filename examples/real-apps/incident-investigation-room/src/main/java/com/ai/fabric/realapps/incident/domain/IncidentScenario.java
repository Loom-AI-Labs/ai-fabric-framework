package com.ai.fabric.realapps.incident.domain;

import java.util.List;
import java.util.Objects;

public record IncidentScenario(
    String id,
    String title,
    String deploymentId,
    String sourceRevision,
    String description,
    List<IncidentEvidence> serviceEvidence,
    List<IncidentEvidence> changeEvidence,
    String failingBranch
) {
    public IncidentScenario {
        id = requireText(id, "id");
        title = requireText(title, "title");
        deploymentId = requireText(deploymentId, "deploymentId");
        sourceRevision = requireText(sourceRevision, "sourceRevision");
        description = requireText(description, "description");
        serviceEvidence = List.copyOf(Objects.requireNonNull(
            serviceEvidence,
            "serviceEvidence is required"
        ));
        changeEvidence = List.copyOf(Objects.requireNonNull(
            changeEvidence,
            "changeEvidence is required"
        ));
        if (serviceEvidence.isEmpty() || changeEvidence.isEmpty()) {
            throw new IllegalArgumentException(
                "Both evidence branches require at least one item"
            );
        }
        failingBranch = normalize(failingBranch);
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

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
