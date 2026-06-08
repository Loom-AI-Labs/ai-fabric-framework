package com.ai.infrastructure.relationship.model.schema;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Schema for a single entity.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EntitySchema {
    private String entityType;
    private String className;
    private String fullClassName;
    private List<FieldInfo> fields;
    private List<RelationshipInfo> relationships;
}
