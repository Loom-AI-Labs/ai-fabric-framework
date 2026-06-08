package com.ai.infrastructure.relationship.service;

import com.ai.infrastructure.relationship.dto.FilterCondition;
import com.ai.infrastructure.relationship.dto.FilterOperator;
import com.ai.infrastructure.relationship.dto.JpqlQuery;
import com.ai.infrastructure.relationship.dto.RelationshipPath;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DynamicJPAQueryBuilderComplexTest {

    private DynamicJPAQueryBuilder builder;
    private EntityRelationshipMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new EntityRelationshipMapper();
        mapper.registerEntityType("document", "com.example.Document");
        mapper.registerEntityType("user", "com.example.User");
        mapper.registerEntityType("project", "com.example.Project");
        mapper.registerRelationship("document", "user", "createdBy");
        mapper.registerRelationship("document", "project", "project");
        builder = new DynamicJPAQueryBuilder(mapper);
    }

    @Test
    void shouldGenerateMultiHopQueryWithFilters() {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("Find docs")
            .primaryEntityType("document")
            .relationshipPaths(List.of(
                RelationshipPath.builder()
                    .fromEntityType("document")
                    .relationshipType("createdBy")
                    .toEntityType("user")
                    .optional(false)
                    .build(),
                RelationshipPath.builder()
                    .fromEntityType("document")
                    .relationshipType("project")
                    .toEntityType("project")
                    .optional(true)
                    .build()
            ))
            .directFilters(Map.of(
                "document", List.of(FilterCondition.builder()
                    .field("status")
                    .operator(FilterOperator.EQUALS)
                    .value("ACTIVE")
                    .build())
            ))
            .relationshipFilters(Map.of(
                "user", List.of(FilterCondition.builder()
                    .field("email")
                    .operator(FilterOperator.ILIKE)
                    .value("%example.com")
                    .build())
            ))
            .limit(25)
            .build();

        JpqlQuery query = builder.buildQuery(plan);

        assertThat(query.getJpql()).contains("SELECT DISTINCT root FROM Document root");
        assertThat(query.getJpql()).contains("JOIN FETCH root.createdBy");
        assertThat(query.getJpql()).contains("LEFT JOIN FETCH root.project");
        assertThat(query.getJpql()).contains("root.status = :p1");
        assertThat(query.getJpql()).contains("LOWER(us1.email) LIKE :p2");
        assertThat(query.getParameters()).hasSize(2);
        assertThat(query.getLimit()).isEqualTo(25);
    }
}
