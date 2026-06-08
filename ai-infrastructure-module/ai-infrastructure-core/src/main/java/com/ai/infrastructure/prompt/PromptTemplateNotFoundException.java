package com.ai.infrastructure.prompt;

/**
 * Thrown when a prompt template resource cannot be found.
 *
 * <p>This enables callers to distinguish "missing template" from other I/O or parsing failures.</p>
 */
public class PromptTemplateNotFoundException extends RuntimeException {

    public PromptTemplateNotFoundException(String message) {
        super(message);
    }
}

