package com.ai.infrastructure.relationship.service;

import com.ai.infrastructure.relationship.dto.JpqlQuery;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;

import java.util.List;

/**
 * Abstraction for executing relationship traversals using different strategies.
 */
public interface RelationshipTraversalService {
    TraversalMode getMode();

    boolean supports(RelationshipQueryPlan plan);

    TraversalResult traverse(RelationshipQueryPlan plan, JpqlQuery query);
}
