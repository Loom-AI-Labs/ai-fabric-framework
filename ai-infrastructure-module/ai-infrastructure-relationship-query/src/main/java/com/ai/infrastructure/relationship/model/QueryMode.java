package com.ai.infrastructure.relationship.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Execution mode defined in the architecture:
 * <ul>
 *     <li>{@link #STANDALONE} – rely purely on relational traversal (no semantic reranking).</li>
 *     <li>{@link #ENHANCED} – combine relational traversal with semantic/vector ranking.</li>
 * </ul>
 */
public enum QueryMode {
    STANDALONE,
    ENHANCED;

    @JsonCreator
    public static QueryMode fromValue(String value) {
        if (value == null || value.isBlank()) {
            return STANDALONE;
        }
        String normalized = value.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .toUpperCase(java.util.Locale.ROOT);
        for (QueryMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return STANDALONE;
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
