package com.ai.infrastructure.relationship.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryStrategyTest {

    @Test
    void shouldMapLegacyRelationshipTraversalValue() {
        assertThat(QueryStrategy.fromValue("RELATIONSHIP_TRAVERSAL"))
            .isEqualTo(QueryStrategy.RELATIONSHIP);
    }

    @Test
    void shouldDefaultToHybridForUnknownValues() {
        assertThat(QueryStrategy.fromValue("UNKNOWN-VALUE"))
            .isEqualTo(QueryStrategy.HYBRID);
    }
}
