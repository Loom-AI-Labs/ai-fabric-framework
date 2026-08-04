package com.ai.fabric.realapps.incident.domain;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.Objects;

public record IncidentEvidence(
    String id,
    String category,
    String summary,
    String severity,
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    Instant observedAt
) {
    public IncidentEvidence {
        id = requireText(id, "id");
        category = requireText(category, "category");
        summary = requireText(summary, "summary");
        severity = requireText(severity, "severity");
        Objects.requireNonNull(observedAt, "observedAt is required");
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
