package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.action.ActionListPayload;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RagResultSummarySupportTest {

    private static final String ANSWER = "answer";
    private static final String DOCUMENTS = "documents";
    private static final String RAG_RESPONSE = "ragResponse";
    private static final String CONFIDENCE = "confidenceScore";
    private static final String CANDIDATE_VECTOR_SPACES = "candidateVectorSpaces";
    private static final String NO_CONTEXT = "No relevant context found.";

    @Test
    void shouldDetectOnlyEmptyListActionPayloads() {
        assertThat(RagResultSummarySupport.isEmptyActionResultPayload(ActionListPayload.of(List.of()))).isTrue();
        assertThat(RagResultSummarySupport.isEmptyActionResultPayload(ActionListPayload.of(List.of("item")))).isFalse();
        assertThat(RagResultSummarySupport.isEmptyActionResultPayload(ActionPayload.object(Map.of()))).isFalse();
        assertThat(RagResultSummarySupport.isEmptyActionResultPayload(null)).isFalse();
    }

    @Test
    void shouldExtractVectorSpacesFromResultOrFallback() {
        OrchestrationResult result = OrchestrationResult.builder()
            .data(Map.of(CANDIDATE_VECTOR_SPACES, List.of("product", " ", "policy")))
            .build();

        assertThat(RagResultSummarySupport.extractVectorSpacesUsed(result, "fallback", CANDIDATE_VECTOR_SPACES))
            .containsExactly("product", "policy");
        assertThat(RagResultSummarySupport.extractVectorSpacesUsed(null, "product, policy", CANDIDATE_VECTOR_SPACES))
            .containsExactly("product", "policy");
        assertThat(RagResultSummarySupport.extractVectorSpacesUsed(null, null, CANDIDATE_VECTOR_SPACES))
            .isEmpty();
    }

    @Test
    void shouldSummarizeRagResultAndExtractAnswer() {
        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message("ok")
            .data(Map.of(ANSWER, "Answer text"))
            .build();

        Map<String, Object> summary = RagResultSummarySupport.summarizeRagResult(result);

        assertThat(summary)
            .containsEntry("type", OrchestrationResultType.INFORMATION_PROVIDED.name())
            .containsEntry("success", true)
            .containsEntry("message", "ok")
            .containsKey("data");
        assertThatThrownBy(() -> summary.put("other", true))
            .isInstanceOf(UnsupportedOperationException.class);
        assertThat(RagResultSummarySupport.summarizeRagResult(null))
            .containsEntry("type", OrchestrationResultType.ERROR.name())
            .containsEntry("success", false);

        assertThat(RagResultSummarySupport.extractAnswer(result, ANSWER)).isEqualTo("Answer text");
        assertThat(RagResultSummarySupport.extractAnswer(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(false)
                .data(Map.of(ANSWER, "Answer text"))
                .build(),
            ANSWER
        )).isNull();
    }

    @Test
    void shouldDetectNoEvidenceOnlyWhenDocsContextAndConfidenceAreEmpty() {
        OrchestrationResult noEvidence = OrchestrationResult.builder()
            .data(Map.of(
                DOCUMENTS, List.of(),
                RAG_RESPONSE, RAGResponse.builder().context(NO_CONTEXT).documents(List.of()).build(),
                CONFIDENCE, 0.0
            ))
            .build();

        assertThat(RagResultSummarySupport.isNoEvidenceRagResult(
            noEvidence,
            DOCUMENTS,
            RAG_RESPONSE,
            CONFIDENCE,
            NO_CONTEXT
        )).isTrue();

        OrchestrationResult withDoc = OrchestrationResult.builder()
            .data(Map.of(
                DOCUMENTS,
                List.of(RAGResponse.RAGDocument.builder().id("doc-1").build()),
                RAG_RESPONSE,
                RAGResponse.builder().context(NO_CONTEXT).build(),
                CONFIDENCE,
                0.0
            ))
            .build();

        assertThat(RagResultSummarySupport.isNoEvidenceRagResult(
            withDoc,
            DOCUMENTS,
            RAG_RESPONSE,
            CONFIDENCE,
            NO_CONTEXT
        )).isFalse();

        OrchestrationResult withContext = OrchestrationResult.builder()
            .data(Map.of(RAG_RESPONSE, RAGResponse.builder().context("useful context").build()))
            .build();
        assertThat(RagResultSummarySupport.isNoEvidenceRagResult(
            withContext,
            DOCUMENTS,
            RAG_RESPONSE,
            CONFIDENCE,
            NO_CONTEXT
        )).isFalse();
    }
}
