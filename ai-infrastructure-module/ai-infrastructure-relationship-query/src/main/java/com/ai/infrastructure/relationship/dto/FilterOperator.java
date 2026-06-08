package com.ai.infrastructure.relationship.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Operator types supported when translating natural-language filters into JPQL predicates.
 * Matches the strategy outlined in {@code ARCHITECTURAL_DECISIONS.md}.
 */
public enum FilterOperator {
    EQUALS,
    NOT_EQUALS,
    GREATER_THAN,
    GREATER_THAN_OR_EQUAL,
    LESS_THAN,
    LESS_THAN_OR_EQUAL,
    LIKE,
    ILIKE,
    IN,
    NOT_IN,
    BETWEEN,
    EXISTS,
    NOT_EXISTS;

    @JsonCreator
    public static FilterOperator fromValue(String value) {
        if (value == null || value.isBlank()) {
            return EQUALS;
        }
        String normalized = value.trim()
            .replace('-', '_')
            .replace(' ', '_')
            .toUpperCase(java.util.Locale.ROOT);
        for (FilterOperator operator : values()) {
            if (operator.name().equals(normalized)) {
                return operator;
            }
        }
        // Default to EQUALS to preserve backwards compatibility with simple filter formats.
        return EQUALS;
    }

    @JsonValue
    public String toJson() {
        return name();
    }
}
