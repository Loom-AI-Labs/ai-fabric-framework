package com.ai.infrastructure.llm.structured;

import com.ai.infrastructure.dto.AIGenerationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;

import java.util.function.Consumer;
import java.util.function.Function;

@Builder
public class StructuredJsonCallSpec<T> {

    /**
     * Human-readable name for logs/metrics (e.g., "intent_extraction", "relationship_query_planner").
     */
    private final String callName;

    /**
     * Total attempts, including the initial call (e.g., 1 => no retry, 2 => 1 retry).
     */
    @Builder.Default
    private final int maxAttempts = 1;

    /**
     * Caller is responsible for generating the LLM request and invoking the provider.
     * The context includes the previous attempt raw content and previous failures for repair prompts.
     */
    private final Function<StructuredJsonAttemptContext, AIGenerationResponse> caller;

    /**
     * Target type for JSON parsing.
     */
    private final Class<T> targetType;

    /**
     * Optional ObjectMapper override (e.g., lenient parsing for specific schemas).
     */
    private final ObjectMapper objectMapper;

    /**
     * Optional domain validation step; throws to signal a retryable validation failure.
     */
    private final Consumer<T> validator;

    /**
     * Whether to retry when the underlying provider call throws.
     */
    @Builder.Default
    private final boolean retryOnCallError = false;

    public String getCallName() {
        return callName;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public Function<StructuredJsonAttemptContext, AIGenerationResponse> getCaller() {
        return caller;
    }

    public Class<T> getTargetType() {
        return targetType;
    }

    public ObjectMapper getObjectMapper() {
        return objectMapper;
    }

    public Consumer<T> getValidator() {
        return validator;
    }

    public boolean isRetryOnCallError() {
        return retryOnCallError;
    }
}

