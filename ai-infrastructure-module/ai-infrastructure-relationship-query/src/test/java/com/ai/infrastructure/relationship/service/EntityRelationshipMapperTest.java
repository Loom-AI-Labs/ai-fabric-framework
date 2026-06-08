package com.ai.infrastructure.relationship.service;

import com.ai.infrastructure.annotation.AICapable;
import com.ai.infrastructure.relationship.dto.RelationshipDirection;
import com.ai.infrastructure.relationship.model.EntityMapping;
import com.ai.infrastructure.relationship.model.RelationshipMapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

class EntityRelationshipMapperTest {

    private EntityRelationshipMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EntityRelationshipMapper();
    }

    @Test
    void shouldRegisterEntityTypeFromAnnotation() {
        EntityMapping mapping = mapper.registerEntityType(DocumentEntity.class);

        assertThat(mapping.entityType()).isEqualTo("document");
        assertThat(mapper.hasEntityType("document")).isTrue();
        assertThat(mapper.getEntityClassName("document")).isEqualTo(DocumentEntity.class.getName());
    }

    @Test
    void shouldRejectDuplicateEntityTypeWithDifferentClass() {
        mapper.registerEntityType("document", DocumentEntity.class);

        assertThatIllegalStateException()
            .isThrownBy(() -> mapper.registerEntityType("document", UserEntity.class))
            .withMessageContaining("already mapped");
    }

    @Test
    void shouldRegisterRelationshipBetweenEntities() {
        mapper.registerEntityType(DocumentEntity.class);
        mapper.registerEntityType(UserEntity.class);

        RelationshipMapping mapping = mapper.registerRelationship(
            "document",
            "user",
            "createdBy",
            RelationshipDirection.REVERSE,
            false
        );

        assertThat(mapping.direction()).isEqualTo(RelationshipDirection.REVERSE);
        RelationshipMapping resolved = mapper.getRelationshipMapping("document", "createdBy");
        assertThat(resolved.fieldName()).isEqualTo("createdBy");
        assertThat(resolved.toEntityType()).isEqualTo("user");
    }

    @Test
    void shouldExposeAllRelationshipMappings() {
        mapper.registerEntityType(DocumentEntity.class);
        mapper.registerEntityType(UserEntity.class);
        mapper.registerEntityType(ProjectEntity.class);

        mapper.registerRelationship("document", "user", "createdBy");
        mapper.registerRelationship("document", "project", "project");

        assertThat(mapper.getAllRelationshipMappings()).hasSize(2);
    }

    @Test
    void shouldFailWhenRelationshipTargetsUnknownEntity() {
        mapper.registerEntityType(DocumentEntity.class);

        assertThatIllegalArgumentException()
            .isThrownBy(() -> mapper.registerRelationship("document", "user", "createdBy"))
            .withMessageContaining("must be registered");
    }

    @Test
    void shouldReturnAllEntityMappingsSnapshot() {
        mapper.registerEntityType(DocumentEntity.class);
        mapper.registerEntityType(UserEntity.class);

        assertThat(mapper.getAllEntityMappings()).hasSize(2);
    }

    @AICapable(entityType = "document")
    private static class DocumentEntity { }

    @AICapable(entityType = "user")
    private static class UserEntity { }

    @AICapable(entityType = "project")
    private static class ProjectEntity { }
}
