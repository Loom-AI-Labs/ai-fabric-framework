package com.ai.fabric.realapps.incident.domain;

import java.util.Objects;
import java.util.Set;

public record IncidentEventQuery(
    Set<IncidentEventType> eventTypes,
    int windowMinutes,
    int limit,
    String sourceName
) {
    public IncidentEventQuery {
        eventTypes = Set.copyOf(Objects.requireNonNull(
            eventTypes,
            "eventTypes are required"
        ));
        if (eventTypes.isEmpty()) {
            throw new IllegalArgumentException("eventTypes are required");
        }
        if (windowMinutes < 5 || windowMinutes > 180) {
            throw new IllegalArgumentException(
                "windowMinutes must be between 5 and 180"
            );
        }
        if (limit < 1 || limit > 6) {
            throw new IllegalArgumentException("limit must be between 1 and 6");
        }
        sourceName = Objects.requireNonNull(
            sourceName,
            "sourceName is required"
        ).trim();
        if (sourceName.isEmpty()) {
            throw new IllegalArgumentException("sourceName is required");
        }
    }
}
