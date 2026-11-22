package ai.fabric.rag.evaluation;

import java.util.List;

/** Actual question, generated answer and authorized retrieved context, not a new retrieval request. */
public record RagEvaluationSample(String question, String answer, List<Evidence> evidence, String referenceAnswer) {
    public RagEvaluationSample {
        if (question == null || question.isBlank() || answer == null || question.length() > 8_000 || answer.length() > 40_000)
            throw new IllegalArgumentException("Evaluation requires a bounded question and answer");
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (evidence.size() > 100 || evidence.stream().mapToInt(e -> e.content().length()).sum() > 200_000)
            throw new IllegalArgumentException("Evaluation evidence exceeds its context allowance");
        if (referenceAnswer != null && referenceAnswer.length() > 20_000)
            throw new IllegalArgumentException("Reference answer exceeds its context allowance");
    }
    public record Evidence(String id, String sourceId, String content) {
        public Evidence {
            if (id == null || id.isBlank() || sourceId == null || content == null || content.length() > 40_000)
                throw new IllegalArgumentException("Evidence requires identity, source and bounded content");
        }
    }
}
