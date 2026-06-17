package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.RagResponseGenerationSupport.ResponseGenerationTrace;
import ai.fabric.spi.AdvancedRAGProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class InformationAdvancedRagExecutionSupport {

    private static final double DEFAULT_RAG_THRESHOLD = 0.6;
    private static final int DEFAULT_RAG_LIMIT = 5;

    private static final String DATA_KEY_ANSWER = "answer";
    private static final String DATA_KEY_DOCUMENTS = "documents";
    private static final String DATA_KEY_RAG_RESPONSE = "ragResponse";
    private static final String DATA_KEY_REQUIRES_GENERATION = "requiresGeneration";
    private static final String DATA_KEY_GENERATION_ERROR = "generationError";
    private static final String DATA_KEY_EXPANDED_QUERIES = "expandedQueries";
    private static final String DATA_KEY_CONFIDENCE_SCORE = "confidenceScore";
    private static final String DATA_KEY_RERANKING_STRATEGY = "rerankingStrategy";
    private static final String DATA_KEY_CONTEXT_OPTIMIZATION_LEVEL = "contextOptimizationLevel";

    private static final String MSG_SEARCH_COMPLETED = "Search completed.";
    private static final String RAG_NO_CONTEXT_MESSAGE = "No relevant context found.";

    private final AdvancedRagSupport advancedRagSupport;
    private final RagResponseGenerationSupport ragResponseGenerationSupport;

    InformationAdvancedRagExecutionSupport(AdvancedRagSupport advancedRagSupport,
                                           RagResponseGenerationSupport ragResponseGenerationSupport) {
        this.advancedRagSupport = advancedRagSupport;
        this.ragResponseGenerationSupport = ragResponseGenerationSupport;
    }

    OrchestrationResult execute(Intent intent,
                                OrchestrationContext context,
                                PipelineContext pipelineContext,
                                boolean needsGeneration,
                                String generationQuery,
                                String retrievalQuery,
                                Map<String, Object> metadata,
                                ReadActionResolutionService.ResolutionOutcome readActionResolution) {
        AdvancedRAGProvider provider = advancedRagSupport.provider();
        if (provider == null) {
            return null;
        }

        try {
            AdvancedRAGRequest request = advancedRagSupport.buildAdvancedRagRequest(
                intent,
                context,
                retrievalQuery,
                metadata,
                pipelineContext,
                DEFAULT_RAG_LIMIT,
                DEFAULT_RAG_THRESHOLD
            );
            AdvancedRAGResponse advancedResponse = provider.performAdvancedRAG(request);
            if (advancedResponse == null) {
                log.warn("Advanced RAG returned null response for request {}",
                    pipelineContext != null ? pipelineContext.getRequestId() : "unknown");
                return null;
            }

            if (Boolean.FALSE.equals(advancedResponse.getSuccess())) {
                log.warn("Advanced RAG failed for request {}: {}",
                    pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                    advancedResponse.getErrorMessage());
                return null;
            }

            List<RAGResponse.RAGDocument> documents = advancedRagSupport.convertToRagDocuments(advancedResponse.getDocuments());
            RAGResponse ragResponse = advancedRagSupport.convertToRagResponse(
                advancedResponse,
                documents,
                generationQuery,
                intent.getVectorSpace()
            );
            String retrievedContext = RagContextSupport.buildGenerationContext(
                documents,
                ragResponse != null ? ragResponse.getContext() : null,
                pipelineContext != null && pipelineContext.getOrchestrationPolicy() != null
                    ? pipelineContext.getOrchestrationPolicy().ragBudgets()
                    : null
            );
            boolean hasRetrievedEvidence = documents != null && !documents.isEmpty();
            if (!hasRetrievedEvidence) {
                hasRetrievedEvidence = StringUtils.hasText(retrievedContext) && !RAG_NO_CONTEXT_MESSAGE.equals(retrievedContext);
            }
            if (!hasRetrievedEvidence && readActionResolution != null && StringUtils.hasText(readActionResolution.evidenceContext())) {
                hasRetrievedEvidence = true;
            }
            boolean lowConfidence = advancedResponse.getConfidenceScore() == null || advancedResponse.getConfidenceScore() <= 0.0d;
            boolean noEvidence = !hasRetrievedEvidence && lowConfidence;

            boolean hasReadActionEvidence = readActionResolution != null
                && StringUtils.hasText(readActionResolution.evidenceContext());
            String answer = null;
            ResponseGenerationTrace generationTrace = null;
            if (needsGeneration) {
                if (StringUtils.hasText(advancedResponse.getResponse()) && !noEvidence && !hasReadActionEvidence) {
                    answer = advancedResponse.getResponse();
                } else {
                    try {
                        String generationContext = hasRetrievedEvidence
                            ? ReadActionResolutionSupport.mergeEvidenceIntoGenerationContext(retrievedContext, pipelineContext, readActionResolution, RAG_NO_CONTEXT_MESSAGE)
                            : retrievedContext;
                        generationTrace = ragResponseGenerationSupport.generateRagAnswer(intent, generationQuery, generationContext, pipelineContext);
                        answer = generationTrace != null ? generationTrace.content() : null;
                    } catch (Exception ex) {
                        log.error("Advanced RAG did not return response and generation fallback failed for request {}: {}",
                            pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                            ex.getMessage(),
                            ex);

                        Map<String, Object> errorData = new LinkedHashMap<>();
                        errorData.put(DATA_KEY_ANSWER, null);
                        errorData.put(DATA_KEY_DOCUMENTS, documents);
                        errorData.put(DATA_KEY_RAG_RESPONSE, ragResponse);
                        errorData.put(DATA_KEY_REQUIRES_GENERATION, true);
                        errorData.put(DATA_KEY_GENERATION_ERROR, ex.getMessage());
                        errorData.put(DATA_KEY_EXPANDED_QUERIES, advancedResponse.getExpandedQueries());
                        errorData.put(DATA_KEY_CONFIDENCE_SCORE, advancedResponse.getConfidenceScore());
                        errorData.put(DATA_KEY_RERANKING_STRATEGY, advancedResponse.getRerankingStrategy());
                        errorData.put(DATA_KEY_CONTEXT_OPTIMIZATION_LEVEL, advancedResponse.getContextOptimizationLevel());

                        return OrchestrationResult.builder()
                            .type(OrchestrationResultType.ERROR)
                            .success(false)
                            .message("Failed to generate response: " + ex.getMessage())
                            .data(Collections.unmodifiableMap(errorData))
                            .nextSteps(extractNextSteps(intent))
                            .build();
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ANSWER, answer);
            data.put(DATA_KEY_DOCUMENTS, documents);
            data.put(DATA_KEY_RAG_RESPONSE, ragResponse);
            data.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);
            data.put(DATA_KEY_EXPANDED_QUERIES, advancedResponse.getExpandedQueries());
            data.put(DATA_KEY_CONFIDENCE_SCORE, advancedResponse.getConfidenceScore());
            data.put(DATA_KEY_RERANKING_STRATEGY, advancedResponse.getRerankingStrategy());
            data.put(DATA_KEY_CONTEXT_OPTIMIZATION_LEVEL, advancedResponse.getContextOptimizationLevel());

            String message = StringUtils.hasText(answer)
                ? answer
                : MSG_SEARCH_COMPLETED;

            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(Boolean.TRUE.equals(advancedResponse.getSuccess()) || advancedResponse.getSuccess() == null)
                .message(message)
                .data(Collections.unmodifiableMap(data))
                .metadata(ragResponseGenerationSupport.responseGenerationMetadata(generationTrace))
                .nextSteps(extractNextSteps(intent))
                .build();
        } catch (Exception ex) {
            log.error("Advanced RAG failed for request {}, falling back to basic RAG: {}",
                pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                ex.getMessage(),
                ex);
            return null;
        }
    }

    private static List<NextStepRecommendation> extractNextSteps(Intent intent) {
        if (intent == null || intent.getNextStepRecommended() == null) {
            return List.of();
        }
        return List.of(intent.getNextStepRecommended());
    }
}
