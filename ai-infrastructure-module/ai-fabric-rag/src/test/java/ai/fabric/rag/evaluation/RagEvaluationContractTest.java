package ai.fabric.rag.evaluation;

import static org.assertj.core.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.evaluation.EvaluationResponse;

class RagEvaluationContractTest {
    private final RagEvaluationSample sample = new RagEvaluationSample("What is delivery?", "Three days", List.of(
        new RagEvaluationSample.Evidence("chunk-1", "policy-1", "Delivery takes three days")), null);
    @Test void carriesTheActualQuestionAnswerAndAttributedEvidence() {
        var adapter = new SpringAiRagEvaluationAdapter("spring-ai-test", Map.of("grounding", request -> {
            assertThat(request.getUserText()).isEqualTo("What is delivery?");
            assertThat(request.getResponseContent()).isEqualTo("Three days");
            assertThat(request.getDataList().getFirst().getMetadata()).containsEntry("sourceId", "policy-1");
            return new EvaluationResponse(true, .8f, "Supported by the supplied context", Map.of());
        }));
        assertThat(adapter.evaluate(sample).metrics().get("grounding").score()).isCloseTo(.8, within(.00001));
    }
    @Test void invalidScoreIsNotConvertedToPassOrZero() {
        var adapter = new SpringAiRagEvaluationAdapter("spring-ai-test", Map.of("grounding", r -> new EvaluationResponse(true, Float.NaN, "", Map.of())));
        assertThat(adapter.evaluate(sample).metrics().get("grounding").status()).isEqualTo(RagEvaluationResult.Metric.Status.NOT_SCORED);
        assertThat(adapter.evaluate(sample).metrics().get("grounding").score()).isNull();
    }
    @Test void providerFailureRemainsVisibleToTheCaller() {
        var adapter = new SpringAiRagEvaluationAdapter("spring-ai-test", Map.of("grounding", r -> { throw new IllegalStateException("provider unavailable"); }));
        assertThatThrownBy(() -> adapter.evaluate(sample)).isInstanceOf(IllegalStateException.class);
    }
    @Test void anotherProviderCanUseTheSameSampleAndResultContract() {
        RagEvaluationProvider provider = new RagEvaluationProvider() {
            public String id() { return "another-evaluator"; }
            public RagEvaluationResult evaluate(RagEvaluationSample input) {
                return new RagEvaluationResult(id(), "rubric-1", Map.of("relevance", new RagEvaluationResult.Metric(
                    RagEvaluationResult.Metric.Status.SCORED, .5, input.question())));
            }
        };
        assertThat(provider.evaluate(sample).providerId()).isEqualTo("another-evaluator");
    }
    @Test void numericAndContextBoundsAreRealContracts() {
        assertThatThrownBy(() -> new RagEvaluationResult.Metric(RagEvaluationResult.Metric.Status.SCORED, 2d, "bad"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RagEvaluationSample("x".repeat(8001), "", List.of(), null)).isInstanceOf(IllegalArgumentException.class);
    }
}
