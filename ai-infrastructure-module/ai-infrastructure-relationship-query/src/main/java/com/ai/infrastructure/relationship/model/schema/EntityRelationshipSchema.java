package com.ai.infrastructure.relationship.model.schema;

import lombok.Builder;
import lombok.Singular;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;

/**
 * Aggregate schema containing all AI-capable entities.
 */
@Builder
public record EntityRelationshipSchema(
    @Singular("entity")
    Map<String, EntitySchema> entities,
    Instant refreshedAt
) {

    public static EntityRelationshipSchema empty() {
        return EntityRelationshipSchema.builder()
            .entities(Collections.emptyMap())
            .refreshedAt(Instant.EPOCH)
            .build();
    }

    public Optional<EntitySchema> find(String entityType) {
        if (entities == null || entityType == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entities.get(entityType.toLowerCase()));
    }

    public int entityCount() {
        return entities != null ? entities.size() : 0;
    }
}
