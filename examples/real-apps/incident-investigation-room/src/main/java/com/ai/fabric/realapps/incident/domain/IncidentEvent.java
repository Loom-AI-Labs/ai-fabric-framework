package com.ai.fabric.realapps.incident.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record IncidentEvent(
    String id,
    String tenantId,
    String incidentId,
    String deploymentId,
    String sourceRevision,
    IncidentEventType type,
    String source,
    String summary,
    String severity,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant observedAt,
    Map<String, Object> safeAttributes
) {
    public IncidentEvent {
        id = requireText(id, "id");
        tenantId = requireText(tenantId, "tenantId");
        incidentId = requireText(incidentId, "incidentId");
        deploymentId = requireText(deploymentId, "deploymentId");
        sourceRevision = requireText(sourceRevision, "sourceRevision");
        Objects.requireNonNull(type, "type is required");
        source = requireText(source, "source");
        summary = requireText(summary, "summary");
        severity = requireText(severity, "severity");
        Objects.requireNonNull(observedAt, "observedAt is required");
        safeAttributes = safeAttributes == null || safeAttributes.isEmpty()
            ? Map.of()
            : Collections.unmodifiableMap(new LinkedHashMap<>(safeAttributes));
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
