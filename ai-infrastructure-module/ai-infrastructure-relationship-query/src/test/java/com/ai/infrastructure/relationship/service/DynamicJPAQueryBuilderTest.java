package com.ai.infrastructure.relationship.service;

import com.ai.infrastructure.annotation.AICapable;
import com.ai.infrastructure.relationship.dto.FilterCondition;
import com.ai.infrastructure.relationship.dto.FilterOperator;
import com.ai.infrastructure.relationship.dto.JpqlQuery;
import com.ai.infrastructure.relationship.dto.RelationshipPath;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import com.ai.infrastructure.relationship.service.EntityRelationshipMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicJPAQueryBuilderTest {

    private DynamicJPAQueryBuilder builder;
    private EntityRelationshipMapper mapper;

    @BeforeEach
    void setup() {
        mapper = new EntityRelationshipMapper();
        mapper.registerEntityType(DocumentEntity.class);
        mapper.registerEntityType(UserEntity.class);
        mapper.registerRelationship("document", "user", "createdBy");
        builder = new DynamicJPAQueryBuilder(mapper);
    }

    @Test
    void shouldBuildQueryWithRelationshipJoinAndFilter() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("Find documents created by active users")
            .primaryEntityType("document")
            .relationshipPaths(List.of(
                RelationshipPath.builder()
                    .fromEntityType("document")
                    .relationshipType("createdBy")
                    .toEntityType("user")
                    .build()
            ))
            .directFilters(Map.of(
                "document", List.of(
                    FilterCondition.builder()
                        .field("status")
                        .operator(FilterOperator.EQUALS)
                        .value("ACTIVE")
                        .build()
                )
            ))
            .build();

        JpqlQuery query = builder.buildQuery(plan);

        assertThat(query.getJpql()).contains("FROM Document");
        assertThat(query.getJpql()).contains("JOIN FETCH root.createdBy");
        assertThat(query.getJpql()).contains("root.status = :p1");
        assertThat(query.getParameters()).containsEntry("p1", "ACTIVE");
    }

    @Test
    void shouldTranslateEntityQualifiedFieldNamesToRegisteredAliases() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("Find active documents")
            .primaryEntityType("document")
            .directFilters(Map.of(
                "document", List.of(
                    FilterCondition.builder()
                        .field("document.status")
                        .operator(FilterOperator.EQUALS)
                        .value("ACTIVE")
                        .build()
                )
            ))
            .build();

        JpqlQuery query = builder.buildQuery(plan);

        assertThat(query.getJpql()).contains("root.status = :p1");
        assertThat(query.getJpql()).doesNotContain("document.status");
    }

    @Test
    void shouldWrapLikeFiltersWithWildcardsWhenMissing() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("Find documents whose title mentions archive")
            .primaryEntityType("document")
            .directFilters(Map.of(
                "document", List.of(
                    FilterCondition.builder()
                        .field("title")
                        .operator(FilterOperator.LIKE)
                        .value("archive")
                        .build()
                )
            ))
            .build();

        JpqlQuery query = builder.buildQuery(plan);

        assertThat(query.getJpql()).contains("LOWER(root.title) LIKE :p1");
        assertThat(query.getParameters()).containsEntry("p1", "%archive%");
    }

    @AICapable(entityType = "document")
    private static class DocumentEntity { }

    @AICapable(entityType = "user")
    private static class UserEntity { }
}
