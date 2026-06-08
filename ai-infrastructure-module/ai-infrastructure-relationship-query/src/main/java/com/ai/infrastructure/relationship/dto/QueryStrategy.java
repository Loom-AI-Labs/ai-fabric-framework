package com.ai.infrastructure.relationship.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Strategy the planner wants to use when executing the query.
 *
 * <ul>
 *     <li>{@link #SEMANTIC} – rely on vector/semantic ranking only.</li>
 *     <li>{@link #RELATIONSHIP} – relational traversal only.</li>
 *     <li>{@link #HYBRID} – mix of relational filtering + semantic reranking.</li>
 * </ul>
 */
public enum QueryStrategy {
    SEMANTIC,
    RELATIONSHIP,
    HYBRID;

    @JsonCreator
    public static QueryStrategy fromValue(String raw) {
        if (raw == null || raw.isBlank()) {
            return HYBRID;
        }

        String normalized = raw.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .toUpperCase(java.util.Locale.ROOT);

        if ("RELATIONSHIP_TRAVERSAL".equals(normalized)) {
            return RELATIONSHIP;
        }

        for (QueryStrategy strategy : values()) {
            if (strategy.name().equals(normalized)) {
                return strategy;
            }
        }
        return HYBRID;
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
