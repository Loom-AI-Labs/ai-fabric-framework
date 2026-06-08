package com.ai.infrastructure.relationship.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Output strategy used by {@code RelationshipQueryService}.
 */
public enum ReturnMode {
    IDS,
    FULL;

    @JsonCreator
    public static ReturnMode fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return IDS;
        }
        String normalized = raw.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .toUpperCase(java.util.Locale.ROOT);
        for (ReturnMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return IDS;
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
