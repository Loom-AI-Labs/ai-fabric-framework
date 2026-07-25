package ai.fabric.indexing.api;

import java.util.Objects;

/**
 * Entity snapshot or delete identity produced by a lifecycle target resolver.
 */
public record AIProcessTarget(
    Class<?> entityClass,
    Object entity,
    String entityId
) {
    public AIProcessTarget {
        Objects.requireNonNull(entityClass, "entityClass is required");
        entityId = entityId == null ? null : entityId.trim();
        if (entity == null && (entityId == null || entityId.isBlank())) {
            throw new IllegalArgumentException("entity or entityId is required");
        }
    }

    public static AIProcessTarget upsert(Object entity) {
        Objects.requireNonNull(entity, "entity is required");
        return new AIProcessTarget(entity.getClass(), entity, null);
    }

    public static AIProcessTarget upsert(Class<?> entityClass, Object entity) {
        Objects.requireNonNull(entity, "entity is required");
        return new AIProcessTarget(entityClass, entity, null);
    }

    public static AIProcessTarget delete(Class<?> entityClass, Object entity) {
        Objects.requireNonNull(entity, "entity is required");
        return new AIProcessTarget(entityClass, entity, null);
    }

    public static AIProcessTarget delete(Class<?> entityClass, String entityId) {
        return new AIProcessTarget(entityClass, null, entityId);
    }
}
