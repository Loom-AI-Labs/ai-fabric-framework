package com.ai.infrastructure.relationship.validation;

/**
 * Raised when a generated relationship query plan fails validation.
 */
public class RelationshipQueryValidationException extends RuntimeException {
    public RelationshipQueryValidationException(String message) {
        super(message);
    }

    public RelationshipQueryValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
