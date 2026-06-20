package ai.fabric.rag.evaluation.springai;

import ai.fabric.dto.RAGResponse;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.evaluation.EvaluationRequest;
import org.springframework.ai.evaluation.EvaluationResponse;
import org.springframework.ai.evaluation.Evaluator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SpringAiRagEvaluationServiceTest {

    @Test
    void mapsRagDocumentsToSpringAiEvaluationRequestWithSafeMetadata() {
        CapturingEvaluator relevancy = new CapturingEvaluator(new EvaluationResponse(
            true,
            0.91f,
            "Relevant",
            Map.of("latencyMs", 12, "prompt", "must-not-leak")
        ));
        SpringAiRagEvaluationService service = new SpringAiRagEvaluationService(relevancy, null);
        RAGResponse ragResponse = ragResponse();

        SpringAiRagEvaluationResult result = service.evaluateRelevancy(
            SpringAiRagEvaluationInput.forGeneratedAnswer(
                "Can I get a refund?",
                ragResponse,
                "Refunds are available within 30 days."
            )
        );

        assertThat(result.pass()).isTrue();
        assertThat(result.score()).isEqualTo(0.91f);
        assertThat(result.feedback()).isEqualTo("Relevant");
        assertThat(result.metadata())
            .containsEntry("evaluator", "spring-ai-relevancy")
            .containsEntry("documentCount", 2)
            .containsEntry("latencyMs", 12)
            .doesNotContainKey("prompt");

        EvaluationRequest captured = relevancy.captured();
        assertThat(captured.getUserText()).isEqualTo("Can I get a refund?");
        assertThat(captured.getResponseContent()).isEqualTo("Refunds are available within 30 days.");
        assertThat(captured.getDataList()).hasSize(2);

        Document first = captured.getDataList().getFirst();
        assertThat(first.getId()).isEqualTo("faq-1");
        assertThat(first.getText()).contains("Refunds are available within 30 days.");
        assertThat(first.getScore()).isEqualTo(0.93d);
        assertThat(first.getMetadata())
            .containsEntry("category", "returns")
            .containsEntry("score", 0.93d)
            .containsEntry("source", "Policy Handbook")
            .doesNotContainKeys("secretToken", "sourceUrl", "embeddingVector");
    }

    @Test
    void returnsFailureWithoutCallingEvaluatorWhenNoDocumentsExist() {
        CapturingEvaluator relevancy = new CapturingEvaluator(new EvaluationResponse(true, 1.0f, "ok", Map.of()));
        SpringAiRagEvaluationService service = new SpringAiRagEvaluationService(relevancy, null);
        RAGResponse response = RAGResponse.builder()
            .context("No context")
            .documents(List.of())
            .build();

        SpringAiRagEvaluationResult result = service.evaluateRelevancy(
            SpringAiRagEvaluationInput.forGeneratedAnswer("question", response, "answer")
        );

        assertThat(result.pass()).isFalse();
        assertThat(result.feedback()).contains("No RAG documents");
        assertThat(relevancy.calls()).isZero();
    }

    @Test
    void reportsMissingFactCheckingEvaluatorAsExplicitFailure() {
        CapturingEvaluator relevancy = new CapturingEvaluator(new EvaluationResponse(true, 1.0f, "ok", Map.of()));
        SpringAiRagEvaluationService service = new SpringAiRagEvaluationService(relevancy, null);

        SpringAiRagEvaluationResult result = service.evaluateFactChecking(
            SpringAiRagEvaluationInput.forGeneratedAnswer("question", ragResponse(), "answer")
        );

        assertThat(result.pass()).isFalse();
        assertThat(result.feedback()).contains("fact-checking evaluator is not configured");
        assertThat(relevancy.calls()).isZero();
    }

    @Test
    void delegatesFactCheckingWhenConfigured() {
        CapturingEvaluator relevancy = new CapturingEvaluator(new EvaluationResponse(true, 1.0f, "unused", Map.of()));
        CapturingEvaluator factChecking = new CapturingEvaluator(new EvaluationResponse(
            false,
            0.2f,
            "Unsupported claim",
            Map.of("provider", "spring-ai")
        ));
        SpringAiRagEvaluationService service = new SpringAiRagEvaluationService(relevancy, factChecking);

        SpringAiRagEvaluationResult result = service.evaluateFactChecking(
            SpringAiRagEvaluationInput.forGeneratedAnswer("question", ragResponse(), "answer")
        );

        assertThat(result.pass()).isFalse();
        assertThat(result.score()).isEqualTo(0.2f);
        assertThat(result.metadata())
            .containsEntry("evaluator", "spring-ai-fact-checking")
            .containsEntry("provider", "spring-ai");
        assertThat(factChecking.calls()).isEqualTo(1);
        assertThat(relevancy.calls()).isZero();
    }

    @Test
    void rejectsBlankInputsBeforeCallingEvaluator() {
        CapturingEvaluator relevancy = new CapturingEvaluator(new EvaluationResponse(true, 1.0f, "ok", Map.of()));
        SpringAiRagEvaluationService service = new SpringAiRagEvaluationService(relevancy, null);

        assertThatThrownBy(() -> service.evaluateRelevancy(
            SpringAiRagEvaluationInput.forGeneratedAnswer(" ", ragResponse(), "answer")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("userText");

        assertThatThrownBy(() -> service.evaluateRelevancy(
            SpringAiRagEvaluationInput.forGeneratedAnswer("question", ragResponse(), " ")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("responseContent");
        assertThat(relevancy.calls()).isZero();
    }

    private RAGResponse ragResponse() {
        return RAGResponse.builder()
            .context("Refunds are available within 30 days.\nWarranty repairs require a support ticket.")
            .documents(List.of(
                RAGResponse.RAGDocument.builder()
                    .id("faq-1")
                    .content("Refunds are available within 30 days.")
                    .title("Refund Policy")
                    .source("Policy Handbook")
                    .score(0.93d)
                    .similarity(0.88d)
                    .metadata(Map.of(
                        "category", "returns",
                        "secretToken", "hidden",
                        "sourceUrl", "https://example.test/private",
                        "embeddingVector", List.of(0.1d, 0.2d)
                    ))
                    .build(),
                RAGResponse.RAGDocument.builder()
                    .id("faq-2")
                    .content("Warranty repairs require a support ticket.")
                    .title("Warranty")
                    .score(0.74d)
                    .metadata(Map.of("category", "support"))
                    .build()
            ))
            .build();
    }

    private static final class CapturingEvaluator implements Evaluator {
        private final EvaluationResponse response;
        private EvaluationRequest captured;
        private int calls;

        private CapturingEvaluator(EvaluationResponse response) {
            this.response = response;
        }

        @Override
        public EvaluationResponse evaluate(EvaluationRequest request) {
            this.captured = request;
            this.calls++;
            return response;
        }

        private EvaluationRequest captured() {
            return captured;
        }

        private int calls() {
            return calls;
        }
    }
}
