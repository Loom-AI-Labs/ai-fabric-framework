package com.ai.infrastructure.relationship.dto;

import lombok.Builder;
import lombok.Value;

import java.util.Collections;
import java.util.Map;

/**
 * Result of translating a {@link RelationshipQueryPlan} into executable JPQL.
 */
@Value
@Builder
public class JpqlQuery {
    String jpql;
    @Builder.Default
    Map<String, Object> parameters = Collections.emptyMap();
    Integer limit;
    @Builder.Default
    String rootAlias = "root";
}
