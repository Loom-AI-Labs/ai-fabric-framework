package com.ai.infrastructure.relationship.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single predicate that can be turned into a JPQL condition.
 * Values are intentionally typed as {@link Object} so they can handle strings,
 * numbers, booleans, or arrays (for {@link FilterOperator#IN} clauses).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
public class FilterCondition {

    /**
     * Fully-qualified field name (e.g. {@code document.status} or {@code user.email}).
     */
    @JsonProperty("field")
    private String field;

    /**
     * Operator describing how to evaluate the filter. Defaults to {@link FilterOperator#EQUALS}.
     */
    @Builder.Default
    @JsonProperty("operator")
    private FilterOperator operator = FilterOperator.EQUALS;

    /**
     * Primary value for the comparison.
     */
    @JsonProperty("value")
    private Object value;

    /**
     * Optional secondary value (e.g. upper bound when using {@link FilterOperator#BETWEEN}).
     */
    @JsonProperty("secondaryValue")
    private Object secondaryValue;

    /**
     * Optional entity type context. This helps planners differentiate between similarly
     * named fields on different entities (document.status vs user.status).
     */
    @JsonProperty("entityType")
    private String entityType;

    /**
     * Some planners may request case-sensitive comparisons; we default to true to keep filtering strict.
     */
    @Builder.Default
    @JsonProperty("caseSensitive")
    private boolean caseSensitive = true;

    public boolean isRangeComparison() {
        return operator == FilterOperator.BETWEEN;
    }
}
