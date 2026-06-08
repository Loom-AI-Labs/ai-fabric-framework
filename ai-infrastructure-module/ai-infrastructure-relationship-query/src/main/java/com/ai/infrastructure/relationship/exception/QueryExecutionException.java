package com.ai.infrastructure.relationship.exception;

/**
 * Raised when executing a relationship query plan fails.
 */
public class QueryExecutionException extends RelationshipQueryException {
    public QueryExecutionException(String message, RelationshipQueryErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
