package com.ai.infrastructure.relationship.dto;

import com.ai.infrastructure.relationship.model.QueryMode;
import com.ai.infrastructure.relationship.model.ReturnMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RelationshipQueryPlanTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldSerializeAndDeserializePlanWithoutLosingContext() throws Exception {
        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery("Find documents created by active users")
            .semanticQuery("documents")
            .primaryEntityType("document")
            .candidateEntityTypes(List.of("document", "user"))
            .preferredMode(QueryMode.STANDALONE)
            .returnMode(ReturnMode.IDS)
            .relationshipPaths(List.of(
                RelationshipPath.builder()
                    .fromEntityType("document")
                    .relationshipType("createdBy")
                    .toEntityType("user")
                    .direction(RelationshipDirection.REVERSE)
                    .conditions(List.of(
                        FilterCondition.builder()
                            .field("user.status")
                            .operator(FilterOperator.EQUALS)
                            .value("active")
                            .build()
                    ))
                    .build()
            ))
            .needsSemanticSearch(false)
            .confidenceScore(0.92)
            .maxTraversalDepth(2)
            .build();

        String json = objectMapper.writeValueAsString(plan);
        RelationshipQueryPlan roundTrip = objectMapper.readValue(json, RelationshipQueryPlan.class);

        assertThat(roundTrip.getPrimaryEntityType()).isEqualTo("document");
        assertThat(roundTrip.getRelationshipPaths()).hasSize(1);
        assertThat(roundTrip.getRelationshipPaths().get(0).getConditions())
            .singleElement()
            .extracting(FilterCondition::getValue)
            .isEqualTo("active");
        assertThat(roundTrip.getPreferredMode()).isEqualTo(QueryMode.STANDALONE);
        assertThat(roundTrip.getReturnMode()).isEqualTo(ReturnMode.IDS);
        assertThat(roundTrip.getConfidenceScore()).isEqualTo(0.92);
        assertThat(roundTrip.getCandidateEntityTypes()).containsExactly("document", "user");
    }
}
