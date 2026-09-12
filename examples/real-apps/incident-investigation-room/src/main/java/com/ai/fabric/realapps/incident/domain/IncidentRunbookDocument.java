package com.ai.fabric.realapps.incident.domain;

import java.util.Map;

public record IncidentRunbookDocument(
    String id,
    String tenantId,
    String deploymentId,
    String sourceRevision,
    String title,
    String content
) {
    public Map<String, Object> vectorMetadata() {
        return Map.of(
            "documentId", id,
            "tenantId", tenantId,
            "deploymentId", deploymentId,
            "sourceRevision", sourceRevision,
            "title", title,
            "sourceType", "RUNBOOK",
            "visibility", "tenant",
            "sourceUrl", "/api/incidents/runbooks/" + id
        );
    }
}
