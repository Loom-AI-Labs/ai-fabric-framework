package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.Intent;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.steps.RagResponseGenerationSupport.ResponseGenerationTrace;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.extractPromptPreview;
import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.hasManagedGenerationPromptOverride;

@Slf4j
final class InformationGenerationResponseSupport {

    private static final String DATA_KEY_ANSWER = "answer";
    private static final String DATA_KEY_DOCUMENTS = "documents";
    private static final String DATA_KEY_RAG_RESPONSE = "ragResponse";
    private static final String DATA_KEY_REQUIRES_GENERATION = "requiresGeneration";
    private static final String DATA_KEY_METADATA = "metadata";
    private static final String DATA_KEY_GENERATION_ERROR = "generationError";

    private static final String METADATA_KEY_SOURCE = "source";
    private static final String METADATA_KEY_USER_ID = "userId";
    private static final String METADATA_KEY_SESSION_ID = "sessionId";
    private static final String METADATA_KEY_AUTHENTICATED = "authenticated";
    private static final String METADATA_VALUE_ORCHESTRATOR = "orchestrator";
    private static final String RAG_NO_CONTEXT_MESSAGE = "No relevant context found.";

    private InformationGenerationResponseSupport() {
    }

    static OrchestrationResult directAnswer(Intent intent,
                                            OrchestrationContext context) {
        String answer = intent != null && StringUtils.hasText(intent.getDirectAnswer())
            ? intent.getDirectAnswer()
            : "Okay.";

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DATA_KEY_ANSWER, answer);
        data.put(DATA_KEY_DOCUMENTS, List.of());
        data.put(DATA_KEY_RAG_RESPONSE, null);
        data.put(DATA_KEY_REQUIRES_GENERATION, false);
        data.put("requiresRetrieval", false);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_KEY_SOURCE, METADATA_VALUE_ORCHESTRATOR);
        metadata.put(METADATA_KEY_USER_ID, context != null ? context.getIdentifier() : null);
        metadata.put(METADATA_KEY_SESSION_ID, context != null ? context.getSessionId() : null);
        metadata.put(METADATA_KEY_AUTHENTICATED, context != null && context.isAuthenticated());
        data.put(DATA_KEY_METADATA, Collections.unmodifiableMap(metadata));

        return OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .message(answer)
            .data(Collections.unmodifiableMap(data))
            .nextSteps(extractNextSteps(intent))
            .build();
    }

    static OrchestrationResult generationOnly(Intent intent,
                                              PipelineContext pipelineContext,
                                              String query,
                                              Map<String, Object> metadata,
                                              RagResponseGenerationSupport generationSupport) {
        ResponseGenerationTrace generationTrace = null;
        String answer;
        try {
            String pinnedTargetsContext = RagContextSupport.prependPinnedTargetsContext(null, pipelineContext);
            if (StringUtils.hasText(pinnedTargetsContext)) {
                generationTrace = generationSupport.generateRagAnswer(intent, query, pinnedTargetsContext, pipelineContext);
                answer = generationTrace != null ? generationTrace.content() : null;
            } else {
                Map<String, String> promptPreview = extractPromptPreview(pipelineContext);
                String prompt = hasManagedGenerationPromptOverride(promptPreview)
                    ? generationSupport.buildRagNoContextPrompt(query, promptPreview)
                    : query;
                generationTrace = generationSupport.generatePromptResponse(
                    prompt,
                    "adhoc",
                    "generation_only",
                    LlmPurpose.GENERATION,
                    "GENERATION_ONLY",
                    null,
                    pipelineContext
                );
                answer = generationTrace != null ? generationTrace.content() : null;
            }
        } catch (Exception ex) {
            log.error("Generation-only response failed for request {}: {}",
                pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                ex.getMessage(),
                ex);
            Map<String, Object> errorData = new LinkedHashMap<>();
            errorData.put(DATA_KEY_ANSWER, null);
            errorData.put(DATA_KEY_DOCUMENTS, List.of());
            errorData.put(DATA_KEY_RAG_RESPONSE, null);
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

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DATA_KEY_ANSWER, answer);
        data.put(DATA_KEY_DOCUMENTS, List.of());
        data.put(DATA_KEY_RAG_RESPONSE, null);
        data.put(DATA_KEY_REQUIRES_GENERATION, true);
        data.put("requiresRetrieval", false);
        if (metadata != null && !metadata.isEmpty()) {
            data.put(DATA_KEY_METADATA, Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
        }

        String message = StringUtils.hasText(answer) ? answer : RAG_NO_CONTEXT_MESSAGE;
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(StringUtils.hasText(answer))
            .message(message)
            .data(Collections.unmodifiableMap(data))
            .metadata(generationSupport.responseGenerationMetadata(generationTrace))
            .nextSteps(extractNextSteps(intent))
            .build();
    }

    static OrchestrationResult fromReadActionEvidence(Intent intent,
                                                      PipelineContext pipelineContext,
                                                      String generationQuery,
                                                      Map<String, Object> metadata,
                                                      ReadActionResolutionService.ResolutionOutcome resolutionOutcome,
                                                      RagResponseGenerationSupport generationSupport) {
        String evidenceContext = ReadActionResolutionSupport.mergeEvidenceIntoGenerationContext(
            null,
            pipelineContext,
            resolutionOutcome,
            RAG_NO_CONTEXT_MESSAGE
        );
        ResponseGenerationTrace generationTrace = null;
        String answer = null;
        try {
            generationTrace = generationSupport.generateRagAnswer(intent, generationQuery, evidenceContext, pipelineContext);
            answer = generationTrace != null ? generationTrace.content() : null;
        } catch (Exception ex) {
            log.error("Read-action evidence generation failed for request {}: {}",
                pipelineContext != null ? pipelineContext.getRequestId() : "unknown",
                ex.getMessage(),
                ex);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put(DATA_KEY_ANSWER, answer);
        data.put(DATA_KEY_DOCUMENTS, List.of());
        data.put(DATA_KEY_RAG_RESPONSE, null);
        data.put(DATA_KEY_REQUIRES_GENERATION, true);
        data.put("requiresRetrieval", false);
        data.put("readActionResolution", resolutionOutcome != null ? resolutionOutcome.diagnostics() : Map.of());
        if (metadata != null && !metadata.isEmpty()) {
            data.put(DATA_KEY_METADATA, Collections.unmodifiableMap(new LinkedHashMap<>(metadata)));
        }

        String message = StringUtils.hasText(answer)
            ? answer
            : (StringUtils.hasText(resolutionOutcome != null ? resolutionOutcome.evidenceContext() : null)
                ? resolutionOutcome.evidenceContext()
                : RAG_NO_CONTEXT_MESSAGE);

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(StringUtils.hasText(answer) || StringUtils.hasText(evidenceContext))
            .message(message)
            .data(Collections.unmodifiableMap(data))
            .metadata(generationSupport.responseGenerationMetadata(generationTrace))
            .nextSteps(extractNextSteps(intent))
            .build();
        return ReadActionResolutionSupport.attachDiagnostics(result, resolutionOutcome);
    }

    private static List<NextStepRecommendation> extractNextSteps(Intent intent) {
        if (intent == null || intent.getNextStepRecommended() == null) {
            return List.of();
        }
        return List.of(intent.getNextStepRecommended());
    }
}
