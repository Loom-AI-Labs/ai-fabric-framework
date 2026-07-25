package ai.fabric.indexing.model;

import ai.fabric.indexing.api.AIContextDataType;

import java.util.Objects;

/**
 * Typed context value supplied to LLM context rendering.
 */
public record AIContextValue(
    Object value,
    AIContextDataType dataType,
    String description
) {
    public AIContextValue {
        Objects.requireNonNull(value, "context value is required");
        Objects.requireNonNull(dataType, "context dataType is required");
        description = description == null ? "" : description;
        if (description.length() > 500) {
            throw new IllegalArgumentException(
                "context description must not exceed 500 characters"
            );
        }
    }
}
