package com.ai.infrastructure.relationship.exception;

/**
 * Raised when the LLM planner fails to build a relationship query plan.
 */
public class QueryPlanningException extends RelationshipQueryException {
    public QueryPlanningException(String message, RelationshipQueryErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
