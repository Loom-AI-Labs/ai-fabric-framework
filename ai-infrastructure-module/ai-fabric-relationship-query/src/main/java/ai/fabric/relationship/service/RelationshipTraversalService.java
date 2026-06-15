package ai.fabric.relationship.service;

import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipQueryPlan;

import java.util.List;

/**
 * Abstraction for executing relationship traversals using different strategies.
 */
public interface RelationshipTraversalService {
    TraversalMode getMode();

    boolean supports(RelationshipQueryPlan plan);

    TraversalResult traverse(RelationshipQueryPlan plan, JpqlQuery query);
}
