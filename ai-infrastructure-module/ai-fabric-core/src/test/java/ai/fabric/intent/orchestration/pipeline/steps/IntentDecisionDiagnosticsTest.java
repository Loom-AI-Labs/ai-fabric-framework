package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationResultNormalizationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IntentDecisionDiagnosticsTest {
    @Test
    void exposesRoutingFlagsWithoutIncludingModelText() {
        var intent = Intent.builder().type(IntentType.INFORMATION).requiresRetrieval(false)
            .requiresGeneration(false).directAnswer("Private model-authored response").build();
        var result = OrchestrationResult.builder().type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true).metadata(Map.of("answerSource", "MODEL_DIRECT_ANSWER")).build();
        var input = PipelineContext.from("A question", OrchestrationContext.anonymous()).toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .intentResult(result).build();

        new MetadataBuildingStep(new OrchestrationResultNormalizationProperties()).process(input);

        assertThat(result.getMetadata()).containsEntry("answerSource", "MODEL_DIRECT_ANSWER");
        assertThat(result.getMetadata().get("intentDecisions")).isEqualTo(List.of(Map.of(
            "type", "INFORMATION", "requiresRetrieval", false, "requiresGeneration", false,
            "directAnswerPresent", true)));
        assertThat(result.getMetadata().toString()).doesNotContain("Private model-authored response");
    }
}
