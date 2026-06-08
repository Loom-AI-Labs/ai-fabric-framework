package com.ai.infrastructure.relationship.model.schema;

import com.ai.infrastructure.relationship.dto.RelationshipDirection;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Describes a relationship from one entity to another.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RelationshipInfo {
    private String fieldName;
    private String targetEntityType;
    private String targetClassName;
    private String relationshipType;
    @Builder.Default
    private RelationshipDirection direction = RelationshipDirection.FORWARD;
    private boolean optional;
}
