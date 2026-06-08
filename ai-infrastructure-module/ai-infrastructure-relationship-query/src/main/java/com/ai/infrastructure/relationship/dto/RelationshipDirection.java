package com.ai.infrastructure.relationship.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Direction of a relationship traversal. This enables the query builder to decide whether the JOIN
 * should be declared from the primary entity ({@code FORWARD}) or the referenced entity
 * ({@code REVERSE}) as described in the relationship traversal diagrams.
 */
public enum RelationshipDirection {
    FORWARD,
    REVERSE,
    BIDIRECTIONAL;

    @JsonCreator
    public static RelationshipDirection fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return FORWARD;
        }
        String normalized = raw.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .toUpperCase(java.util.Locale.ROOT);
        for (RelationshipDirection direction : values()) {
            if (direction.name().equals(normalized)) {
                return direction;
            }
        }
        return FORWARD;
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
