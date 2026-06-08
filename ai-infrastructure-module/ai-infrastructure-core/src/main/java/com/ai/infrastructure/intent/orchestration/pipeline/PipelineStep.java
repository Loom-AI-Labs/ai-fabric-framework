package com.ai.infrastructure.intent.orchestration.pipeline;

/**
 * A single step in the orchestration pipeline.
 * 
 * <p>Each step receives a {@link PipelineContext}, processes it, and returns
 * an updated context. Steps can signal early termination by calling
 * {@link PipelineContext#terminate(com.ai.infrastructure.intent.orchestration.OrchestrationResult)}.</p>
 * 
 * <p><strong>Thread Safety:</strong> Implementations must be thread-safe as
 * multiple requests may execute concurrently.</p>
 * 
 * <p><strong>Design Guidelines:</strong></p>
 * <ul>
 *   <li>Each step should have a single responsibility</li>
 *   <li>Steps should be stateless (use injected services for state)</li>
 *   <li>All string literals must be extracted to constants</li>
 *   <li>Steps should log their actions at appropriate levels</li>
 * </ul>
 * 
 * <p><strong>Implementation Example:</strong></p>
 * <pre>{@code
 * @Slf4j
 * @Component
 * @RequiredArgsConstructor
 * public class MyCustomStep implements PipelineStep {
 *     
 *     private static final String STEP_NAME = "MyCustomStep";
 *     
 *     private final MyService myService;
 *     
 *     @Override
 *     public String getStepName() {
 *         return STEP_NAME;
 *     }
 *     
 *     @Override
 *     public int getOrder() {
 *         return 50; // Custom priority
 *     }
 *     
 *     @Override
 *     public PipelineContext process(PipelineContext context) {
 *         // Process and return updated context
 *         return context;
 *     }
 * }
 * }</pre>
 * 
 * @see Pipeline
 * @see PipelineContext
 * @see DefaultOrchestrationPipeline
 * @since 1.0
 */
public interface PipelineStep {
    
    /**
     * Process this step of the pipeline.
     * 
     * <p>Implementations should:</p>
     * <ul>
     *   <li>Check {@link #shouldSkip(PipelineContext)} if overridden</li>
     *   <li>Process the context and return an updated version</li>
     *   <li>Call {@link PipelineContext#terminate} for early exit with error</li>
     *   <li>Log significant actions at DEBUG level, errors at WARN/ERROR</li>
     * </ul>
     * 
     * @param context the current pipeline context (never null)
     * @return updated context (never null), may include termination signal
     * @throws IllegalArgumentException if context is null
     */
    PipelineContext process(PipelineContext context);
    
    /**
     * Get the unique name of this step for logging and metrics.
     * 
     * <p>Names should be:</p>
     * <ul>
     *   <li>Descriptive and meaningful (e.g., "SecurityAnalysis", "PIIDetection")</li>
     *   <li>Defined as constants (no magic strings)</li>
     *   <li>Unique across all steps in the pipeline</li>
     * </ul>
     * 
     * @return the step name (never null or blank)
     */
    String getStepName();
    
    /**
     * Get the execution order of this step.
     * 
     * <p>Lower values execute earlier. Standard ordering:</p>
     * <ul>
     *   <li>10 - Security Analysis</li>
     *   <li>20 - Access Control</li>
     *   <li>30 - PII Detection</li>
     *   <li>40 - Compliance Check</li>
     *   <li>50 - Intent Extraction</li>
     *   <li>60 - Intent Handling</li>
     *   <li>70 - Metadata Building</li>
     *   <li>80 - Smart Suggestions</li>
     *   <li>90 - Response Sanitization</li>
     *   <li>100 - History Persistence</li>
     * </ul>
     * 
     * @return the order value (default 100)
     */
    default int getOrder() {
        return 100;
    }
    
    /**
     * Determine if this step should be skipped.
     * 
     * <p>By default, steps are skipped if the pipeline has already been
     * terminated (e.g., due to security or access control failure).</p>
     * 
     * <p>Override to add custom skip logic (e.g., feature flags).</p>
     * 
     * @param context the current pipeline context
     * @return true if this step should be skipped, false otherwise
     */
    default boolean shouldSkip(PipelineContext context) {
        return context != null && context.isShouldTerminate();
    }
}
