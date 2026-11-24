package ai.fabric.llm.structured;

import ai.fabric.dto.AIGenerationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultStructuredJsonCallExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructuredJsonExtractor extractor = new StructuredJsonExtractor();
    private final DefaultStructuredJsonCallExecutor executor = new DefaultStructuredJsonCallExecutor(extractor, objectMapper);

    @Test void preservesEmptyVersusTruncatedAndSafeProviderMetadata() {
        var outcome = new ai.fabric.dto.ProviderOutcome("workflow", "model", 8192, "request-1", "length", 12L, 20L, null, 0, "SUCCESS");
        var empty = executor.execute(StructuredJsonCallSpec.<Map>builder().targetType(Map.class)
            .caller(c -> AIGenerationResponse.builder().content("").providerOutcome(outcome).build()).build());
        assertThat(empty.getLastFailure().type()).isEqualTo(StructuredJsonFailureType.EMPTY_RESPONSE);
        assertThat(empty.getLastFailure().providerOutcome()).isEqualTo(outcome);
        var truncated = executor.execute(StructuredJsonCallSpec.<Map>builder().targetType(Map.class)
            .caller(c -> AIGenerationResponse.builder().content("{\"value\":").providerOutcome(outcome).build()).build());
        assertThat(truncated.getLastFailure().type()).isEqualTo(StructuredJsonFailureType.TRUNCATED_RESPONSE);
        assertThat(truncated.getLastFailure().truncationSuspected()).isTrue();
        assertThat(truncated.getLastFailure().message()).doesNotContain("value");
    }

    @Test void wrappedTypedTransportObservationDoesNotExposeRawExceptionBodies() {
        var safe = new ai.fabric.dto.ProviderOutcome("workflow", "model", null, "request-1", null, null, null, null, 0, "RATE_LIMIT", 30000L);
        var result = executor.execute(StructuredJsonCallSpec.<Map>builder().targetType(Map.class)
            .caller(c -> { throw new IllegalStateException("private provider body credential", new ProviderOutcomeException(safe)); }).build());
        assertThat(result.getLastFailure().type()).isEqualTo(StructuredJsonFailureType.CALL_ERROR);
        assertThat(result.getLastFailure().providerOutcome()).isEqualTo(safe);
        assertThat(result.getLastFailure().message()).doesNotContain("private", "credential");
    }

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

