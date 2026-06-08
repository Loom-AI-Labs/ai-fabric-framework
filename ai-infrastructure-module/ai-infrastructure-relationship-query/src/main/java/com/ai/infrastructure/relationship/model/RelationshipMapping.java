package com.ai.infrastructure.relationship.model;

import com.ai.infrastructure.relationship.dto.RelationshipDirection;

import java.util.Objects;

/**
 * Represents a mapping between two entity types and the field used to traverse from one to the other.
 */
public record RelationshipMapping(
    String fromEntityType,
    String toEntityType,
    String fieldName,
    RelationshipDirection direction,
    boolean optional
) {

    public RelationshipMapping {
        Objects.requireNonNull(fromEntityType, "fromEntityType is required");
        Objects.requireNonNull(toEntityType, "toEntityType is required");

        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName cannot be blank");
        }

        direction = direction == null ? RelationshipDirection.FORWARD : direction;
    }

    public boolean isReverse() {
        return direction == RelationshipDirection.REVERSE;
    }

    public boolean isBidirectional() {
        return direction == RelationshipDirection.BIDIRECTIONAL;
    }
}
