package com.ai.fabric.realapps.incident.domain;

import java.util.List;
import java.util.Objects;

public record IncidentPlanRequest(
    String question,
    String incidentId,
    String deploymentId,
    String sourceRevision,
    List<IncidentEvidence> serviceEvidence,
    List<IncidentEvidence> changeEvidence,
    String failingBranch
) {
    public IncidentPlanRequest {
        question = requireText(question, "question");
        incidentId = requireText(incidentId, "incidentId");
        deploymentId = requireText(deploymentId, "deploymentId");
        sourceRevision = requireText(sourceRevision, "sourceRevision");
        serviceEvidence = List.copyOf(Objects.requireNonNull(
            serviceEvidence,
            "serviceEvidence is required"
        ));
        changeEvidence = List.copyOf(Objects.requireNonNull(
            changeEvidence,
            "changeEvidence is required"
        ));
        failingBranch = failingBranch == null || failingBranch.isBlank()
            ? null
            : failingBranch.trim();
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
