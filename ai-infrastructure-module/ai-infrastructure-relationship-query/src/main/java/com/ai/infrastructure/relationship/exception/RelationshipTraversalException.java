package com.ai.infrastructure.relationship.exception;

/**
 * Raised when metadata-based traversal fails.
 */
public class RelationshipTraversalException extends RelationshipQueryException {
    public RelationshipTraversalException(String message, RelationshipQueryErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
