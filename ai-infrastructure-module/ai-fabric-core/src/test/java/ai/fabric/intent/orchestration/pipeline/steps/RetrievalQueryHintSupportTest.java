package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RetrievalQueryHintSupportTest {

    @Test
    void shouldApplyValidHintWhenExactlyOneRetrievalIntentExists() {
        Intent retrievalIntent = retrievalIntent("search");
        PipelineContext context = PipelineContext.from("query", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(retrievalIntent))
                .metadata(Map.of("retrievalQueryHint", " sku-123 "))
                .build())
            .build();
        Map<String, Object> metadata = new LinkedHashMap<>();

        String query = RetrievalQueryHintSupport.applyRetrievalQueryHint(
            "gaming laptop",
            context,
            retrievalIntent,
            metadata
        );

        assertThat(query).isEqualTo("gaming laptop sku-123");
        assertThat(metadata).containsEntry("retrievalQueryHintApplied", true);
    }

    @Test
    void shouldRejectHintWhenMultipleRetrievalIntentsExist() {
        Intent first = retrievalIntent("first");
        Intent second = retrievalIntent("second");
        PipelineContext context = PipelineContext.from("query", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(first, second))
                .metadata(Map.of("retrievalQueryHint", "sku-123"))
                .build())
            .build();
        Map<String, Object> metadata = new LinkedHashMap<>();

        String query = RetrievalQueryHintSupport.applyRetrievalQueryHint("laptop", context, first, metadata);

        assertThat(query).isEqualTo("laptop");
        assertThat(metadata).containsEntry("retrievalQueryHintApplied", false);
    }

    @Test
    void shouldRejectUnsafeHints() {
        assertThat(RetrievalQueryHintSupport.isSafeRetrievalQueryHint("sku-123")).isTrue();
        assertThat(RetrievalQueryHintSupport.isSafeRetrievalQueryHint("user@example.com")).isFalse();
        assertThat(RetrievalQueryHintSupport.isSafeRetrievalQueryHint("sku\n123")).isFalse();
        assertThat(RetrievalQueryHintSupport.isSafeRetrievalQueryHint("sku  123")).isFalse();
        assertThat(RetrievalQueryHintSupport.isSafeRetrievalQueryHint("x".repeat(201))).isFalse();
    }

    @Test
    void shouldCountOnlyRetrievalIntents() {
        assertThat(RetrievalQueryHintSupport.hasExactlyOneRetrievalIntent(
            MultiIntentResponse.builder()
                .intents(List.of(
                    retrievalIntent("search"),
                    Intent.builder().type(IntentType.INFORMATION).requiresRetrieval(false).build()
                ))
                .build()
        )).isTrue();

        assertThat(RetrievalQueryHintSupport.hasExactlyOneRetrievalIntent(
            MultiIntentResponse.builder()
                .intents(List.of(Intent.builder().type(IntentType.INFORMATION).requiresRetrieval(false).build()))
                .build()
        )).isFalse();
    }

    private Intent retrievalIntent(String name) {
        return Intent.builder()
            .type(IntentType.INFORMATION)
            .intent(name)
            .requiresRetrieval(true)
            .build();
    }
}
