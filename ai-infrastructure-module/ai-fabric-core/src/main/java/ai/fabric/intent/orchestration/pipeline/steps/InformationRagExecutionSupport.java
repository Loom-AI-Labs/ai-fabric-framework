package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.RagResponseGenerationSupport.ResponseGenerationTrace;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.spi.RAGProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
final class InformationRagExecutionSupport {

    private static final double DEFAULT_RAG_THRESHOLD = 0.6;
    private static final int DEFAULT_RAG_LIMIT = 5;
    private static final double DEFAULT_FAN_OUT_RAG_THRESHOLD = 0.3d;

    private static final String DATA_KEY_ANSWER = "answer";
    private static final String DATA_KEY_DOCUMENTS = "documents";
    private static final String DATA_KEY_RAG_RESPONSE = "ragResponse";
    private static final String DATA_KEY_REQUIRES_GENERATION = "requiresGeneration";
    private static final String DATA_KEY_DETAILS = "details";
    private static final String DATA_KEY_CANDIDATE_VECTOR_SPACES = "candidateVectorSpaces";
    private static final String DATA_KEY_ROUTING_STRATEGY = "vectorSpaceRoutingStrategy";
    private static final String DATA_KEY_GENERATION_ERROR = "generationError";

    private static final String MSG_SEARCH_COMPLETED = "Search completed.";
    private static final String RAG_NO_CONTEXT_MESSAGE = "No relevant context found.";
    private static final String ERROR_MSG_RAG_NULL_RESPONSE = "RAG provider returned null response";

    private final ObjectProvider<RAGProvider> ragProvider;
    private final AdvancedRagSupport advancedRagSupport;
    private final VectorSpaceRoutingProperties vectorSpaceRoutingProperties;
    private final RankBasedMerger rankBasedMerger;
    private final RagResponseGenerationSupport ragResponseGenerationSupport;

    InformationRagExecutionSupport(ObjectProvider<RAGProvider> ragProvider,
                                   AdvancedRagSupport advancedRagSupport,
                                   VectorSpaceRoutingProperties vectorSpaceRoutingProperties,
                                   RankBasedMerger rankBasedMerger,
                                   RagResponseGenerationSupport ragResponseGenerationSupport) {
        this.ragProvider = ragProvider;
        this.advancedRagSupport = advancedRagSupport;
        this.vectorSpaceRoutingProperties = vectorSpaceRoutingProperties;
        this.rankBasedMerger = rankBasedMerger;
        this.ragResponseGenerationSupport = ragResponseGenerationSupport;
    }

    OrchestrationResult basic(Intent intent,
                              OrchestrationContext context,
                              PipelineContext pipelineContext,
                              boolean needsGeneration,
                              String generationQuery,
                              String retrievalQuery,
                              Map<String, Object> metadata,
                              OrchestrationPolicy.RagBudgets ragBudgets,
                              ReadActionResolutionService.ResolutionOutcome readActionResolution) {
        RAGProvider provider = ragProvider != null ? ragProvider.getIfAvailable() : null;
        if (provider == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ANSWER, null);
            data.put(DATA_KEY_DOCUMENTS, List.of());
            data.put(DATA_KEY_RAG_RESPONSE, null);
            data.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);
            data.put(DATA_KEY_DETAILS, "RAG module is not enabled (no RAGProvider bean present).");

            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(false)
                .message(RAG_NO_CONTEXT_MESSAGE)
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        int limit = DEFAULT_RAG_LIMIT;
        if (ragBudgets != null && ragBudgets.maxDocumentsReturnedToClient() != null && ragBudgets.maxDocumentsReturnedToClient() > 0) {
            limit = ragBudgets.maxDocumentsReturnedToClient();
        }

        RAGRequest ragRequest = RAGRequest.builder()
            .query(retrievalQuery)
            .entityType(intent.getVectorSpace())
            .limit(limit)
            .threshold(advancedRagSupport.resolveSimilarityThreshold(ragBudgets, DEFAULT_RAG_THRESHOLD))
            .metadata(Collections.unmodifiableMap(metadata))
            .authContext(OrchestrationAuthContextResolver.from(context))
            .build();

        RAGResponse ragResponse = needsGeneration
            ? provider.performRAGQuery(ragRequest)
            : provider.performRag(ragRequest);
        if (ragResponse == null) {
            return OrchestrationResult.error(ERROR_MSG_RAG_NULL_RESPONSE);
        }

        String answer = null;
        ResponseGenerationTrace generationTrace = null;
        if (needsGeneration) {
            try {
                List<RAGResponse.RAGDocument> docs = ragResponse.getDocuments() != null ? ragResponse.getDocuments() : List.of();
                String baseContext = RagContextSupport.buildGenerationContext(docs, ragResponse.getContext(), ragBudgets);

                boolean hasRetrievedEvidence = !docs.isEmpty();
                if (!hasRetrievedEvidence) {
                    hasRetrievedEvidence = StringUtils.hasText(baseContext) && !RAG_NO_CONTEXT_MESSAGE.equals(baseContext);
                }
                if (!hasRetrievedEvidence && readActionResolution != null && StringUtils.hasText(readActionResolution.evidenceContext())) {
                    hasRetrievedEvidence = true;
                }
                String generationContext = hasRetrievedEvidence
                    ? ReadActionResolutionSupport.mergeEvidenceIntoGenerationContext(baseContext, pipelineContext, readActionResolution, RAG_NO_CONTEXT_MESSAGE)
                    : baseContext;
                generationTrace = ragResponseGenerationSupport.generateRagAnswer(intent, generationQuery, generationContext, pipelineContext);
                answer = generationTrace != null ? generationTrace.content() : null;
            } catch (Exception ex) {
                log.error("RAG generation failed for request {}: {}",
                    pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                    ex.getMessage(),
                    ex);

                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put(DATA_KEY_ANSWER, null);
                errorData.put(DATA_KEY_DOCUMENTS, ragResponse.getDocuments());
                errorData.put(DATA_KEY_RAG_RESPONSE, ragResponse);
                errorData.put(DATA_KEY_REQUIRES_GENERATION, true);
                errorData.put(DATA_KEY_GENERATION_ERROR, ex.getMessage());

                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ERROR)
                    .success(false)
                    .message("Failed to generate response: " + ex.getMessage())
                    .data(Collections.unmodifiableMap(errorData))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DATA_KEY_ANSWER, answer);
        data.put(DATA_KEY_DOCUMENTS, ragResponse.getDocuments());
        data.put(DATA_KEY_RAG_RESPONSE, ragResponse);
        data.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);

        String message = StringUtils.hasText(answer)
            ? answer
            : MSG_SEARCH_COMPLETED;

        return OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(Boolean.TRUE.equals(ragResponse.getSuccess()) || ragResponse.getSuccess() == null)
            .message(message)
            .data(Collections.unmodifiableMap(data))
            .metadata(ragResponseGenerationSupport.responseGenerationMetadata(generationTrace))
            .nextSteps(extractNextSteps(intent))
            .build();
    }

    OrchestrationResult fanOut(Intent intent,
                               OrchestrationContext context,
                               PipelineContext pipelineContext,
                               boolean deterministic,
                               boolean needsGeneration,
                               String generationQuery,
                               String retrievalQuery,
                               Map<String, Object> metadata,
                               List<String> vectorSpaces,
                               OrchestrationPolicy.RagBudgets ragBudgets,
                               ReadActionResolutionService.ResolutionOutcome readActionResolution) {
        RAGProvider provider = ragProvider != null ? ragProvider.getIfAvailable() : null;
        if (provider == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_ANSWER, null);
            data.put(DATA_KEY_DOCUMENTS, List.of());
            data.put(DATA_KEY_RAG_RESPONSE, null);
            data.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);
            data.put(DATA_KEY_DETAILS, "RAG module is not enabled (no RAGProvider bean present).");
            data.put(DATA_KEY_CANDIDATE_VECTOR_SPACES, vectorSpaces);
            data.put(DATA_KEY_ROUTING_STRATEGY, "FAN_OUT");

            return OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(false)
                .message(RAG_NO_CONTEXT_MESSAGE)
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }

        int topKPerSpace = ragBudgets != null && ragBudgets.topKPerSpace() != null && ragBudgets.topKPerSpace() > 0
            ? ragBudgets.topKPerSpace()
            : (vectorSpaceRoutingProperties != null
                ? vectorSpaceRoutingProperties.getFanOutTopKPerSpace()
                : DEFAULT_RAG_LIMIT);

        double fanOutThreshold = vectorSpaceRoutingProperties != null
            ? vectorSpaceRoutingProperties.getFanOutRagThreshold()
            : DEFAULT_FAN_OUT_RAG_THRESHOLD;
        fanOutThreshold = advancedRagSupport.resolveSimilarityThreshold(ragBudgets, fanOutThreshold);

        Map<String, List<RAGResponse.RAGDocument>> docsBySpace = new LinkedHashMap<>();
        for (String vectorSpace : vectorSpaces) {
            RAGRequest ragRequest = RAGRequest.builder()
                .query(retrievalQuery)
                .entityType(vectorSpace)
                .limit(topKPerSpace)
                .threshold(fanOutThreshold)
                .metadata(Collections.unmodifiableMap(new LinkedHashMap<>(metadata)))
                .authContext(OrchestrationAuthContextResolver.from(context))
                .build();

            RAGResponse ragResponse = needsGeneration
                ? provider.performRAGQuery(ragRequest)
                : provider.performRag(ragRequest);

            List<RAGResponse.RAGDocument> docs = ragResponse != null && ragResponse.getDocuments() != null
                ? ragResponse.getDocuments()
                : List.of();

            List<RAGResponse.RAGDocument> tagged = docs.stream()
                .filter(java.util.Objects::nonNull)
                .map(doc -> RagContextSupport.tagDocumentWithVectorSpace(doc, vectorSpace))
                .toList();

            docsBySpace.put(vectorSpace, tagged);
        }

        List<RAGResponse.RAGDocument> merged = rankBasedMerger.mergeByRank(docsBySpace, topKPerSpace);
        merged = rankBasedMerger.dedupePreserveOrder(merged, doc -> doc != null ? doc.getId() : null);

        if (ragBudgets != null
            && ragBudgets.maxDocumentsReturnedToClient() != null
            && ragBudgets.maxDocumentsReturnedToClient() > 0
            && merged.size() > ragBudgets.maxDocumentsReturnedToClient()) {
            merged = merged.subList(0, ragBudgets.maxDocumentsReturnedToClient());
        }

        Double bestScore = RagContextSupport.bestDocumentScore(merged);
        double threshold = vectorSpaceRoutingProperties != null
            ? vectorSpaceRoutingProperties.getClarificationThreshold()
            : 0.4d;
        boolean hasReadActionEvidence = readActionResolution != null
            && readActionResolution.hasGroundingEvidence()
            && StringUtils.hasText(readActionResolution.evidenceContext());

        boolean weakFanOutEvidence = merged.isEmpty() || (bestScore != null && bestScore < threshold);
        if (!deterministic
            && weakFanOutEvidence
            && !hasReadActionEvidence
            && !needsGeneration) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put(DATA_KEY_CANDIDATE_VECTOR_SPACES, vectorSpaces);
            data.put(DATA_KEY_ROUTING_STRATEGY, "FAN_OUT");
            if (bestScore != null) {
                data.put("bestScore", bestScore);
            }

            return OrchestrationResult.builder()
                .type(OrchestrationResultType.CLARIFICATION_REQUIRED)
                .success(false)
                .message("I couldn't confidently determine which domain to search. Please specify one of: "
                    + String.join(", ", vectorSpaces))
                .data(Collections.unmodifiableMap(data))
                .nextSteps(extractNextSteps(intent))
                .build();
        }
        if (!deterministic && weakFanOutEvidence && (hasReadActionEvidence || needsGeneration)) {
            metadata.put(
                hasReadActionEvidence ? "fanoutClarificationSuppressedByReadActionEvidence" : "fanoutClarificationSuppressedByGeneration",
                true
            );
            if (bestScore != null) {
                metadata.put("fanoutSuppressedBestScore", bestScore);
            }
        }

        int docsForContext = Math.min(RagContextSupport.resolveGenerationContextDocumentLimit(ragBudgets), merged.size());
        Integer maxContextChars = RagContextSupport.resolveGenerationContextMaxChars(ragBudgets);
        String mergedContext = RagContextSupport.buildContextFromDocuments(merged.subList(0, docsForContext), maxContextChars);
        RAGResponse mergedResponse = RAGResponse.builder()
            .documents(merged)
            .context(mergedContext)
            .originalQuery(generationQuery)
            .entityType(String.join(",", vectorSpaces))
            .metadata(metadata != null && !metadata.isEmpty()
                ? Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
                : null)
            .success(true)
            .build();

        String answer = null;
        ResponseGenerationTrace generationTrace = null;
        if (needsGeneration) {
            try {
                boolean hasRetrievedEvidence = merged != null && !merged.isEmpty();
                if (!hasRetrievedEvidence) {
                    hasRetrievedEvidence = StringUtils.hasText(mergedContext) && !RAG_NO_CONTEXT_MESSAGE.equals(mergedContext);
                }
                if (!hasRetrievedEvidence && readActionResolution != null && StringUtils.hasText(readActionResolution.evidenceContext())) {
                    hasRetrievedEvidence = true;
                }
                String generationContext = hasRetrievedEvidence
                    ? ReadActionResolutionSupport.mergeEvidenceIntoGenerationContext(mergedContext, pipelineContext, readActionResolution, RAG_NO_CONTEXT_MESSAGE)
                    : mergedContext;
                generationTrace = ragResponseGenerationSupport.generateRagAnswer(intent, generationQuery, generationContext, pipelineContext);
                answer = generationTrace != null ? generationTrace.content() : null;
            } catch (Exception ex) {
                log.error("Fan-out RAG generation failed for request {}: {}",
                    pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                    ex.getMessage(),
                    ex);

                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put(DATA_KEY_ANSWER, null);
                errorData.put(DATA_KEY_DOCUMENTS, merged);
                errorData.put(DATA_KEY_RAG_RESPONSE, mergedResponse);
                errorData.put(DATA_KEY_REQUIRES_GENERATION, true);
                errorData.put(DATA_KEY_GENERATION_ERROR, ex.getMessage());
                errorData.put(DATA_KEY_CANDIDATE_VECTOR_SPACES, vectorSpaces);
                errorData.put(DATA_KEY_ROUTING_STRATEGY, "FAN_OUT");

                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.ERROR)
                    .success(false)
                    .message("Failed to generate response: " + ex.getMessage())
                    .data(Collections.unmodifiableMap(errorData))
                    .nextSteps(extractNextSteps(intent))
                    .build();
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DATA_KEY_ANSWER, answer);
        data.put(DATA_KEY_DOCUMENTS, merged);
        data.put(DATA_KEY_RAG_RESPONSE, mergedResponse);
        data.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);
        data.put(DATA_KEY_CANDIDATE_VECTOR_SPACES, vectorSpaces);
        data.put(DATA_KEY_ROUTING_STRATEGY, "FAN_OUT");
        if (bestScore != null) {
            data.put("bestScore", bestScore);
        }

        String message = StringUtils.hasText(answer)
            ? answer
            : MSG_SEARCH_COMPLETED;

        return OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message(message)
            .data(Collections.unmodifiableMap(data))
            .metadata(ragResponseGenerationSupport.responseGenerationMetadata(generationTrace))
            .nextSteps(extractNextSteps(intent))
            .build();
    }

    private static List<NextStepRecommendation> extractNextSteps(Intent intent) {
        if (intent == null || intent.getNextStepRecommended() == null) {
            return List.of();
        }
        return List.of(intent.getNextStepRecommended());
    }
}
