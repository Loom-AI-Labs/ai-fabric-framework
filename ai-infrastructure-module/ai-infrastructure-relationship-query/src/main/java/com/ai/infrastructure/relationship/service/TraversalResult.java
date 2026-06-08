package com.ai.infrastructure.relationship.service;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Holds the output of executing a relationship traversal.
 *
 * <p>For JPA traversals, this includes both the in-memory entities returned by JPQL and their IDs.
 * For ID-only traversals, {@link #entities()} may be empty.</p>
 */
public record TraversalResult(List<?> entities, List<String> entityIds) {

    public static TraversalResult empty() {
        return new TraversalResult(Collections.emptyList(), Collections.emptyList());
    }

    public static TraversalResult ids(List<String> entityIds) {
        return new TraversalResult(Collections.emptyList(), safe(entityIds));
    }

    public static TraversalResult entities(List<?> entities, List<String> entityIds) {
        return new TraversalResult(safe(entities), safe(entityIds));
    }

    private static <T> List<T> safe(List<T> values) {
        return values == null ? Collections.emptyList() : values;
    }

    public TraversalResult {
        entities = safe(entities);
        entityIds = safe(entityIds);
        Objects.requireNonNull(entities, "entities");
        Objects.requireNonNull(entityIds, "entityIds");
    }
}

