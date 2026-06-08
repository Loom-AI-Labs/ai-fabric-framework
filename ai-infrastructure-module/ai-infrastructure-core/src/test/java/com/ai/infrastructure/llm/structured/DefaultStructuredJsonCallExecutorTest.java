package com.ai.infrastructure.llm.structured;

import com.ai.infrastructure.dto.AIGenerationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStructuredJsonCallExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructuredJsonExtractor extractor = new StructuredJsonExtractor();
    private final DefaultStructuredJsonCallExecutor executor = new DefaultStructuredJsonCallExecutor(extractor, objectMapper);

    @Test
    void shouldRetryOnceOnParseFailureAndReturnParsedValue() {
        AtomicInteger calls = new AtomicInteger();

        StructuredJsonResult<Map> result = executor.execute(
            StructuredJsonCallSpec.<Map>builder()
                .callName("test")
                .maxAttempts(2)
                .targetType(Map.class)
                .objectMapper(objectMapper)
                .caller(ctx -> {
                    if (calls.getAndIncrement() == 0) {
                        return AIGenerationResponse.builder().content("{\"a\": ").build();
                    }
                    return AIGenerationResponse.builder().content("{\"a\": 1}").build();
                })
                .build()
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAttempts()).isEqualTo(2);
        assertThat(result.getFailures()).hasSize(1);
        assertThat(result.getValue()).containsEntry("a", 1);
    }
}

