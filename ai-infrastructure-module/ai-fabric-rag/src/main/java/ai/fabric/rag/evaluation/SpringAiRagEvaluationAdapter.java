package ai.fabric.rag.evaluation;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.Evaluator;

/** Adapts configured Spring AI evaluators; never chooses a model or starts retrieval. */
public final class SpringAiRagEvaluationAdapter implements RagEvaluationProvider {
    private final String id;
    private final Map<String, Evaluator> evaluators;
    public SpringAiRagEvaluationAdapter(String id, Map<String, Evaluator> evaluators) {
        if (id == null || id.isBlank() || evaluators == null || evaluators.isEmpty() || evaluators.size() > 8)
            throw new IllegalArgumentException("A provider identity and one to eight evaluators are required");
        this.id = id; this.evaluators = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(evaluators));
    }
    @Override public String id() { return id; }
    @Override public RagEvaluationResult evaluate(RagEvaluationSample sample) {
        var request = new EvaluationRequest(sample.question(), sample.evidence().stream()
            .map(e -> new Document(e.content(), Map.of("sourceId", e.sourceId(), "evidenceId", e.id()))).toList(), sample.answer());
        Map<String, RagEvaluationResult.Metric> metrics = new LinkedHashMap<>();
        for (var entry : evaluators.entrySet()) {
            var response = entry.getValue().evaluate(request);
            if (response == null || !Float.isFinite(response.getScore()) || response.getScore() < 0 || response.getScore() > 1) {
                metrics.put(entry.getKey(), new RagEvaluationResult.Metric(RagEvaluationResult.Metric.Status.NOT_SCORED, null,
                    "The configured evaluator did not return a valid numeric score."));
            } else {
                metrics.put(entry.getKey(), new RagEvaluationResult.Metric(RagEvaluationResult.Metric.Status.SCORED,
                    (double) response.getScore(), response.getFeedback() == null ? "" : response.getFeedback()));
            }
        }
        return new RagEvaluationResult(id, "spring-ai-evaluator-v1", metrics);
    }
}
