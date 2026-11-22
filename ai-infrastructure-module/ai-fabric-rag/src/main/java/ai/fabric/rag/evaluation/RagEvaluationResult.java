package ai.fabric.rag.evaluation;

import java.util.Map;

public record RagEvaluationResult(String providerId, String rubricVersion, Map<String, Metric> metrics) {
    public RagEvaluationResult {
        if (providerId == null || providerId.isBlank() || rubricVersion == null || rubricVersion.isBlank())
            throw new IllegalArgumentException("An evaluation result requires provider and rubric identity");
        metrics = Map.copyOf(metrics);
        if (metrics.isEmpty()) throw new IllegalArgumentException("An evaluation result requires metric outcomes");
    }
    public record Metric(Status status, Double score, String explanation) {
        public enum Status { SCORED, NOT_SCORED, FAILED }
        public Metric {
            if (status == null || explanation == null || explanation.length() > 8_000)
                throw new IllegalArgumentException("A bounded metric outcome is required");
            if (status == Status.SCORED && (score == null || !Double.isFinite(score) || score < 0 || score > 1))
                throw new IllegalArgumentException("A scored metric must have a finite score from zero to one");
            if (status != Status.SCORED && score != null) throw new IllegalArgumentException("An unscored outcome cannot carry a numeric score");
        }
    }
}
