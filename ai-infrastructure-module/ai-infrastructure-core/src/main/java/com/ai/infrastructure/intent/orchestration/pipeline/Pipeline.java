package com.ai.infrastructure.intent.orchestration.pipeline;

import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;

import java.util.List;

/**
 * Executes a series of {@link PipelineStep}s in order to process orchestration requests.
 * 
 * <p>The pipeline is the core execution engine for the RAG orchestrator. It takes a query
 * and context, processes them through a series of steps (security, access control, PII,
 * compliance, intent extraction, etc.), and returns the final result.</p>
 * 
 * <p><strong>Execution Flow:</strong></p>
 * <ol>
 *   <li>Create initial {@link PipelineContext} from query and orchestration context</li>
 *   <li>Execute each step in order (sorted by {@link PipelineStep#getOrder()})</li>
 *   <li>Check for early termination after each step</li>
 *   <li>Return final {@link OrchestrationResult}</li>
 * </ol>
 * 
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe as multiple
 * requests may execute concurrently.</p>
 * 
 * <p><strong>Error Handling:</strong> If any step throws an exception, the pipeline
 * should catch it and return an error result. Steps should handle their own expected
 * errors and use {@link PipelineContext#terminate} for controlled early exit.</p>
 * 
 * @see PipelineStep
 * @see PipelineContext
 * @see DefaultOrchestrationPipeline
 * @since 1.0
 */
public interface Pipeline {
    
    /**
     * Execute all steps in the pipeline to process the given query.
     * 
     * <p>This method:</p>
     * <ul>
     *   <li>Validates inputs (query and context must not be null)</li>
     *   <li>Creates a {@link PipelineContext} to carry state through steps</li>
     *   <li>Executes each step in order</li>
     *   <li>Handles early termination from any step</li>
     *   <li>Returns the final orchestration result</li>
     * </ul>
     * 
     * @param query the user's query string (must not be null or blank)
     * @param context the orchestration context containing user/session info (must not be null)
     * @return the orchestration result (never null)
     * @throws IllegalArgumentException if query is null/blank or context is null
     */
    OrchestrationResult execute(String query, OrchestrationContext context);
    
    /**
     * Get all registered pipeline steps in execution order.
     * 
     * <p>The returned list is sorted by {@link PipelineStep#getOrder()} and is
     * unmodifiable.</p>
     * 
     * @return unmodifiable list of pipeline steps (never null)
     */
    List<PipelineStep> getSteps();
    
    /**
     * Get the number of steps in the pipeline.
     * 
     * @return the step count
     */
    default int getStepCount() {
        return getSteps().size();
    }
}
