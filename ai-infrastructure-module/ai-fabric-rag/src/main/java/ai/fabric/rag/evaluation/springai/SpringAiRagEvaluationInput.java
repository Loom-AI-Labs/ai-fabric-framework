package ai.fabric.rag.evaluation.springai;

import ai.fabric.dto.RAGResponse;
import org.springframework.util.StringUtils;

import java.util.Objects;

/**
 * Input passed to Spring AI-backed RAG evaluators.
 */
public record SpringAiRagEvaluationInput(
    String userText,
    RAGResponse ragResponse,
    String responseContent
) {

    public SpringAiRagEvaluationInput {
        if (!StringUtils.hasText(userText)) {
            throw new IllegalArgumentException("userText is required");
        }
        Objects.requireNonNull(ragResponse, "ragResponse is required");
        if (!StringUtils.hasText(responseContent)) {
            throw new IllegalArgumentException("responseContent is required");
        }
        userText = userText.trim();
        responseContent = responseContent.trim();
    }

    public static SpringAiRagEvaluationInput forRetrievedContext(String userText, RAGResponse ragResponse) {
        Objects.requireNonNull(ragResponse, "ragResponse is required");
        return new SpringAiRagEvaluationInput(userText, ragResponse, ragResponse.getContext());
    }

    public static SpringAiRagEvaluationInput forGeneratedAnswer(String userText,
                                                                RAGResponse ragResponse,
                                                                String generatedAnswer) {
        return new SpringAiRagEvaluationInput(userText, ragResponse, generatedAnswer);
    }
}
