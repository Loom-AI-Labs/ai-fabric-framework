package com.ai.infrastructure.relationship.exception;

/**
 * Raised when every fallback strategy has failed.
 */
public class FallbackExhaustedException extends RelationshipQueryException {
    public FallbackExhaustedException(String message, RelationshipQueryErrorContext context) {
        super(message, context);
    }

    public FallbackExhaustedException(String message, RelationshipQueryErrorContext context, Throwable cause) {
        super(message, context, cause);
    }
}
