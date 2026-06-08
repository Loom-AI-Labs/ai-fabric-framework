package com.ai.infrastructure.llm.structured;

public interface StructuredJsonCallExecutor {
    <T> StructuredJsonResult<T> execute(StructuredJsonCallSpec<T> spec);
}

