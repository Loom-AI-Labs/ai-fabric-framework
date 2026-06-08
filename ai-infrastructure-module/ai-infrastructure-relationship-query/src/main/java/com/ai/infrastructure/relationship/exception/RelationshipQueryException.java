package com.ai.infrastructure.relationship.exception;

import java.util.Optional;

/**
 * Base runtime exception for all relationship query failures.
 */
public class RelationshipQueryException extends RuntimeException {
    private final RelationshipQueryErrorContext context;

    public RelationshipQueryException(String message, RelationshipQueryErrorContext context) {
        super(message);
        this.context = context;
    }

    public RelationshipQueryException(String message, RelationshipQueryErrorContext context, Throwable cause) {
        super(message, cause);
        this.context = context;
    }

    public Optional<RelationshipQueryErrorContext> getContext() {
        return Optional.ofNullable(context);
    }
}
