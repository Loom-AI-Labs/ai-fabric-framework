package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.ProgressiveIntentExtractionEngine;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pipeline step that extracts user intent from the query using LLM analysis.
 * 
 * <p>This step uses the {@link IntentQueryExtractor} to analyze the processed
 * query and extract structured intents. The LLM determines intent type (ACTION,
 * INFORMATION, OUT_OF_SCOPE), action names, and parameters. Multiple intents are
 * represented as multiple entries in the {@code intents[]} array.</p>
 * 
 * <p><strong>Order:</strong> 50 (after compliance check)</p>
 * 
 * <p><strong>LLM Decision Respect:</strong> Per framework philosophy, the LLM's
 * analysis of the specific query is respected. Configuration provides constraints,
 * not overrides.</p>
 * 
 * <p><strong>Termination:</strong> If no intents can be extracted from the query,
 * the pipeline is terminated with an error result.</p>
 * 
 * @see IntentQueryExtractor
 * @see MultiIntentResponse
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentExtractionStep implements PipelineStep {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "IntentExtraction";
    private static final int STEP_ORDER = 50;
    private static final String EXTRACTION_DIAGNOSTICS_KEY = "extractionDiagnostics";
    
    // Error messages
    private static final String ERROR_MSG_NO_INTENT = "Unable to determine user intent.";
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final IntentQueryExtractor intentQueryExtractor;
    private final ObjectProvider<ProgressiveIntentExtractionEngine> progressiveEngineProvider;
    
    // =========================================================================
    // PipelineStep Implementation
    // =========================================================================
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String getStepName() {
        return STEP_NAME;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return STEP_ORDER;
    }
    
    /**
     * Extract user intent from the query using LLM analysis.
     * 
     * <p>This step:</p>
     * <ol>
     *   <li>Passes the processed query to the intent extractor</li>
     *   <li>The LLM analyzes the query and returns structured intents</li>
     *   <li>If no intents are extracted, terminates with error</li>
     *   <li>Otherwise, updates context with the intent response</li>
     * </ol>
     * 
     * <p>The LLM's intent analysis is respected as authoritative for this
     * specific query context.</p>
     * 
     * @param context the current pipeline context
     * @return updated context with intent response, or terminated if no intent
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Extracting intent for request {}", context.getRequestId());
        
        String userQuery = context.getEffectiveQuery();
        String currentUserMessage = buildCurrentUserMessage(context.getPinnedTargetsContext(), userQuery);
        IntentExtractionInput input = new IntentExtractionInput(
            userQuery,
            currentUserMessage,
            context.getHistoryMessages(),
            context.getOrchestrationRequest() != null
                    && context.getOrchestrationRequest().trustedExecutionContext() != null
                ? OrchestrationAuthContextResolver.from(
                    context.getOrchestrationRequest().trustedExecutionContext()
                )
                : OrchestrationAuthContextResolver.from(
                    context.getOrchestrationContext()
                )
        );

        ProgressiveIntentExtractionEngine engine = progressiveEngineProvider != null
            ? progressiveEngineProvider.getIfAvailable()
            : null;

        MultiIntentResponse intentResponse;
        PipelineContext updatedContext = context.withMetadata(
            "llmPrompting",
            Map.of(
                "standard", "MULTI_MESSAGE",
                "historyMessagesCount", input.historyMessages() != null ? input.historyMessages().size() : 0,
                "currentUserMessageChars", currentUserMessage != null ? currentUserMessage.length() : 0,
                "pinnedTargetsContextChars", context.getPinnedTargetsContext() != null ? context.getPinnedTargetsContext().length() : 0
            )
        );

        try {
            if (engine != null) {
                ProgressiveIntentExtractionEngine.ExtractionOutput output = engine.extract(
                    input,
                    context.getOrchestrationContext()
                );
                intentResponse = output != null ? output.response() : null;
                if (output != null && output.diagnostics() != null && !output.diagnostics().isEmpty()) {
                    updatedContext = updatedContext.withMetadata(EXTRACTION_DIAGNOSTICS_KEY, output.diagnostics());
                }
            } else {
                IntentQueryExtractor.ExtractionTrace extractionTrace = intentQueryExtractor.extractWithTrace(
                    input,
                    context.getOrchestrationContext()
                );
                intentResponse = extractionTrace != null
                    ? extractionTrace.response()
                    : intentQueryExtractor.extract(input, context.getOrchestrationContext());
                updatedContext = updatedContext.withMetadata(EXTRACTION_DIAGNOSTICS_KEY, directExtractionDiagnostics(extractionTrace));
            }
        } catch (Exception ex) {
            log.warn("Intent extraction failed for request {}: {}", context.getRequestId(), ex.getMessage());
            updatedContext = updatedContext.withMetadata(
                "intentExtractionError",
                Map.of("message", ex.getMessage(), "fallback", true)
            );
            intentResponse = fallbackIntentResponse("Intent extraction failed: " + safeMessage(ex));
        }
        
        if (!intentResponse.hasIntents()) {
            log.warn("No intents extracted for query '{}' in request {}", 
                userQuery, context.getRequestId());
            updatedContext = updatedContext.withMetadata(
                "intentExtractionError",
                Map.of("message", ERROR_MSG_NO_INTENT, "fallback", true)
            );
            intentResponse = fallbackIntentResponse(ERROR_MSG_NO_INTENT);
        }
        
        int intentCount = intentResponse.getIntents().size();
        boolean isCompound = intentCount > 1;
        
        log.debug("Extracted {} intent(s) for request {} (compound: {})", 
            intentCount, context.getRequestId(), isCompound);
        
        return updatedContext.toBuilder()
            .intentResponse(intentResponse)
            .build();
    }

    private String buildCurrentUserMessage(String pinnedTargetsContext, String userQuery) {
        if (!StringUtils.hasText(pinnedTargetsContext)) {
            return userQuery != null ? userQuery : "";
        }
        if (!StringUtils.hasText(userQuery)) {
            return pinnedTargetsContext;
        }
        return pinnedTargetsContext.trim() + "\n\n" + userQuery.trim();
    }

    private MultiIntentResponse fallbackIntentResponse(String reason) {
        Intent fallbackIntent = Intent.builder()
            .type(IntentType.OUT_OF_SCOPE)
            .intent("out_of_scope")
            .confidence(0.0d)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .actionParams(Map.of("reason", StringUtils.hasText(reason) ? reason : "unknown"))
            .build();

        return MultiIntentResponse.builder()
            .intents(List.of(fallbackIntent))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .metadata(Map.of("fallback", true))
            .build();
    }

    private String safeMessage(Exception ex) {
        if (ex == null) {
            return "unknown";
        }
        String message = ex.getMessage();
        return StringUtils.hasText(message) ? message : ex.getClass().getSimpleName();
    }

    private Map<String, Object> directExtractionDiagnostics(IntentQueryExtractor.ExtractionTrace trace) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("extractionPath", "single_pass");
        diagnostics.put("extractionAttempts", 1);
        diagnostics.put("llmCalls", 1);
        if (trace != null && trace.processingTimeMs() != null) {
            diagnostics.put("processingTimeMs", trace.processingTimeMs());
        }
        if (trace != null && trace.providerProcessingTimeMs() != null) {
            diagnostics.put("providerProcessingTimeMs", trace.providerProcessingTimeMs());
        }
        if (trace != null && StringUtils.hasText(trace.model())) {
            diagnostics.put("model", trace.model());
        }

        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("strategy", "single_pass");
        attempt.put("success", trace != null && trace.response() != null && trace.response().hasIntents());
        attempt.put("llmCalls", 1);
        if (trace != null && trace.processingTimeMs() != null) {
            attempt.put("processingTimeMs", trace.processingTimeMs());
        }
        if (trace != null && trace.providerProcessingTimeMs() != null) {
            attempt.put("providerProcessingTimeMs", trace.providerProcessingTimeMs());
        }
        if (trace != null && StringUtils.hasText(trace.model())) {
            attempt.put("model", trace.model());
        }
        diagnostics.put("attempts", List.of(Map.copyOf(attempt)));
        return Map.copyOf(diagnostics);
    }
}
