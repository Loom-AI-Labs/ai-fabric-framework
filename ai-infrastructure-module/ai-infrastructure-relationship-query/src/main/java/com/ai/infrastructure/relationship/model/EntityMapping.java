package com.ai.infrastructure.relationship.model;

import java.util.Objects;

/**
 * Represents a single entity mapping connecting a logical entity type (e.g. {@code "document"})
 * to an actual JPA entity class name.
 */
public record EntityMapping(String entityType, String className, Class<?> entityClass) {

    public EntityMapping {
        Objects.requireNonNull(entityType, "entityType is required");
        if (entityType.isBlank()) {
            throw new IllegalArgumentException("entityType cannot be blank");
        }

        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("className cannot be blank");
        }
    }

    /**
     * Returns the short class name for display purposes.
     */
    public String simpleClassName() {
        if (entityClass != null) {
            return entityClass.getSimpleName();
        }
        int idx = className.lastIndexOf('.');
        return idx >= 0 ? className.substring(idx + 1) : className;
    }
}
