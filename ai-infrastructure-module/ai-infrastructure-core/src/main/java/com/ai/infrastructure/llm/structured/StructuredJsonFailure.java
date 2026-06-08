package com.ai.infrastructure.llm.structured;

public record StructuredJsonFailure(
    StructuredJsonFailureType type,
    String message,
    boolean truncationSuspected
) {
}

