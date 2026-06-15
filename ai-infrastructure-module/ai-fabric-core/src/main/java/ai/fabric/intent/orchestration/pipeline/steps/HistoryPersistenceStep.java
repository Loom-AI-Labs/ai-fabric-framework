package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.intent.history.IntentHistoryService;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Pipeline step that persists the intent history for analytics and auditing.
 * 
 * <p>This step records the original query, extracted intents, and result
 * to the intent history service for later analysis. It runs at the end
 * of the pipeline to capture the complete processing outcome.</p>
 * 
 * <p><strong>Order:</strong> 100 (final step)</p>
 * 
 * <p><strong>Non-Fatal:</strong> Failures in history persistence do not
 * cause the request to fail. Errors are logged but the pipeline continues.</p>
 * 
 * @see IntentHistoryService
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HistoryPersistenceStep implements PipelineStep {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "HistoryPersistence";
    private static final int STEP_ORDER = 100;
    
    // Metadata keys
    private static final String METADATA_KEY_SESSION_ID = "sessionId";
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final IntentHistoryService intentHistoryService;
    
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

    @Override
    public boolean shouldSkip(PipelineContext context) {
        return false;
    }
    
    /**
     * Persist the intent history.
     * 
     * <p>This step records the processing history including:</p>
     * <ul>
     *   <li>User/session identifier</li>
     *   <li>Session ID</li>
     *   <li>Original query</li>
     *   <li>Extracted intents</li>
     *   <li>Processing result</li>
     * </ul>
     * 
     * <p><strong>Non-Fatal:</strong> Any errors during persistence are
     * logged but do not fail the request.</p>
     * 
     * @param context the current pipeline context
     * @return unchanged context (history persistence is a side effect)
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Persisting history for request {}", context.getRequestId());
        
        OrchestrationResult result = context.getIntentResult();
        if (result == null) {
            log.debug("No intent result to persist for request {}", context.getRequestId());
            return context;
        }
        
        try {
            String sessionId = extractSessionId(result);
            
            intentHistoryService.recordIntent(
                context.getIdentifier(),
                sessionId,
                context.getOriginalQuery(),
                context.getIntentResponse(),
                result
            );
            
            log.debug("Intent history persisted for request {}", context.getRequestId());
            
        } catch (Exception ex) {
            // Non-fatal - log but don't fail the request
            log.debug("Unable to persist intent history for user {} in request {}: {}", 
                context.getIdentifier(), context.getRequestId(), ex.getMessage());
        }
        
        return context;
    }
    
    // =========================================================================
    // Private Helper Methods
    // =========================================================================
    
    /**
     * Extract session ID from result metadata.
     * 
     * @param result the orchestration result
     * @return session ID or null
     */
    private String extractSessionId(OrchestrationResult result) {
        if (result.getMetadata() == null) {
            return null;
        }
        Object sessionId = result.getMetadata().get(METADATA_KEY_SESSION_ID);
        return sessionId != null ? sessionId.toString() : null;
    }
}
