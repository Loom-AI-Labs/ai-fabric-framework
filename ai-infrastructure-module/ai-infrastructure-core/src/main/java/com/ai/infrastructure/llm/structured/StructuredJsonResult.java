package com.ai.infrastructure.llm.structured;

import lombok.Builder;

import java.util.List;

@Builder
public class StructuredJsonResult<T> {

    private final boolean success;
    private final T value;
    private final int attempts;
    private final List<StructuredJsonFailure> failures;
    private final StructuredJsonFailure lastFailure;

    public boolean isSuccess() {
        return success;
    }

    public T getValue() {
        return value;
    }

    public int getAttempts() {
        return attempts;
    }

    public List<StructuredJsonFailure> getFailures() {
        return failures;
    }

    public StructuredJsonFailure getLastFailure() {
        return lastFailure;
    }
}

