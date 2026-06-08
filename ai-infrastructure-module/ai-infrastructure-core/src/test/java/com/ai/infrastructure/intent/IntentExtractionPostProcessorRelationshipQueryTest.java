package com.ai.infrastructure.intent;

import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.NextStepRecommendation;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IntentExtractionPostProcessorRelationshipQueryTest {

    @Test
    void shouldConvertNextStepQueryIntoPostActionGenerationRequest() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findMetadata("relationship_query"))
            .thenReturn(Optional.of(AIActionMetaData.builder().name("relationship_query").build()));

        IntentExtractionPostProcessor processor = new IntentExtractionPostProcessor(registry);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("relationship_query")
            .action("relationship_query")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .actionParams(Map.of("entityTypes", List.of("product")))
            .nextStepRecommended(NextStepRecommendation.builder()
                .intent("any_follow_up")
                .query("Explain the results in one sentence.")
                .confidence(0.9d)
                .vectorSpace(null)
                .build())
            .build();

        MultiIntentResponse processed = processor.postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "relationship_query: show me products and then summarize"
        );

        Intent out = processed.getIntents().getFirst();
        assertThat(out.getRequiresGeneration()).isTrue();
        assertThat(out.getGenerationInstructions()).isEqualTo("Explain the results in one sentence.");
    }

    @Test
    void shouldCanonicalizeActionNameAliasesForPostActionGeneration() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findMetadata("relationship query"))
            .thenReturn(Optional.of(AIActionMetaData.builder().name("relationship_query").build()));

        IntentExtractionPostProcessor processor = new IntentExtractionPostProcessor(registry);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("relationship query")
            .action("relationship query")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .actionParams(Map.of("entityTypes", List.of("product")))
            .build();

        MultiIntentResponse processed = processor.postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "relationship query: show me products"
        );

        Intent out = processed.getIntents().getFirst();
        assertThat(out.getAction()).isEqualTo("relationship_query");
        assertThat(processed.getMetadata()).containsKey("normalization");
        Object normalization = processed.getMetadata().get("normalization");
        assertThat(normalization).isInstanceOf(Map.class);
        Object rules = ((Map<?, ?>) normalization).get("appliedRules");
        assertThat(rules).isInstanceOf(List.class);
        List<String> ruleIds = ((List<?>) rules).stream().map(Object::toString).toList();
        assertThat(ruleIds).contains("CANONICALIZE_ACTION_NAME");
    }

    @Test
    void shouldNotConvertNextStepWhenVectorSpaceIsPresent() {
        AIActionRegistry registry = mock(AIActionRegistry.class);
        when(registry.findMetadata("relationship_query"))
            .thenReturn(Optional.of(AIActionMetaData.builder().name("relationship_query").build()));

        IntentExtractionPostProcessor processor = new IntentExtractionPostProcessor(registry);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("relationship_query")
            .action("relationship_query")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .actionParams(Map.of("entityTypes", List.of("product")))
            .nextStepRecommended(NextStepRecommendation.builder()
                .intent("search_kb")
                .query("Find the refund policy")
                .vectorSpace("policies")
                .confidence(0.9d)
                .build())
            .build();

        MultiIntentResponse processed = processor.postProcess(
            MultiIntentResponse.builder().intents(List.of(intent)).build(),
            "relationship_query: show me products"
        );

        Intent out = processed.getIntents().getFirst();
        assertThat(out.getRequiresGeneration()).isFalse();
        assertThat(out.getGenerationInstructions()).isNull();
    }
}
