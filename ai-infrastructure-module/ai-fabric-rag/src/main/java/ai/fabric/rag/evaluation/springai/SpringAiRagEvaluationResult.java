package ai.fabric.rag.evaluation.springai;

import org.springframework.ai.evaluation.EvaluationResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable AI Fabric result shape for Spring AI RAG evaluator responses.
 */
public record SpringAiRagEvaluationResult(
    boolean pass,
    float score,
    String feedback,
    Map<String, Object> metadata
) {

    public SpringAiRagEvaluationResult {
        feedback = feedback == null ? "" : feedback;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    static SpringAiRagEvaluationResult from(EvaluationResponse response,
                                            String evaluator,
                                            int documentCount,
                                            Map<String, Object> safeMetadata) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evaluator", evaluator);
        metadata.put("documentCount", documentCount);
        if (safeMetadata != null) {
            metadata.putAll(safeMetadata);
        }
        if (response == null) {
            return new SpringAiRagEvaluationResult(false, 0.0f, "Evaluator returned no response", metadata);
        }
        return new SpringAiRagEvaluationResult(
            response.isPass(),
            response.getScore(),
            response.getFeedback(),
            metadata
        );
    }

    static SpringAiRagEvaluationResult failed(String evaluator, String feedback) {
        return new SpringAiRagEvaluationResult(false, 0.0f, feedback, Map.of("evaluator", evaluator));
    }
}
