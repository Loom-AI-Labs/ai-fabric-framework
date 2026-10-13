package ai.fabric.intent.orchestration;

import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * RAG Orchestrator - Main entry point for intent-based query processing.
 * 
 * <p>The orchestrator delegates processing to a {@link Pipeline} that executes
 * a series of steps: security analysis, access control, PII detection, compliance
 * validation, intent extraction, intent handling, smart suggestions, response
 * sanitization, and history persistence.</p>
 * 
 * <p><strong>Thread Safety:</strong> This class is thread-safe. Multiple concurrent
 * calls to {@link #orchestrate} are supported.</p>
 * 
 * <p><strong>Usage:</strong></p>
 * <pre>{@code
 * // Authenticated user
 * OrchestrationContext ctx = OrchestrationContext.builder()
 *     .userId("user-123")
 *     .sessionId("sess-abc")
 *     .build();
 * OrchestrationResult result = orchestrator.orchestrate("Cancel my subscription", ctx);
 * 
 * // Anonymous user (information-only)
 * OrchestrationContext anon = OrchestrationContext.forSession("sess-xyz");
 * OrchestrationResult info = orchestrator.orchestrate("Show refund policy", anon);
 * }</pre>
 * 
 * @see Pipeline
 * @see OrchestrationContext
 * @see OrchestrationResult
 * @since 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RAGOrchestrator {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String ERROR_NULL_QUERY = "query must not be null";
    private static final String ERROR_NULL_CONTEXT = "context must not be null";
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final Pipeline pipeline;
    
    // =========================================================================
    // Public API
    // =========================================================================
    
    /**
     * Orchestrate a query through the processing pipeline.
     * 
     * <p>This method processes the query through all pipeline steps:</p>
     * <ol>
     *   <li>Security Analysis - Block malicious requests</li>
     *   <li>Access Control - Verify permissions</li>
     *   <li>PII Detection - Redact sensitive information</li>
     *   <li>Compliance Check - Validate against policies</li>
     *   <li>Intent Extraction - Analyze query with LLM</li>
     *   <li>Intent Handling - Execute actions or retrieve information</li>
     *   <li>Metadata Building - Enrich result with context</li>
     *   <li>Smart Suggestions - Generate related recommendations</li>
     *   <li>Response Sanitization - Clean output for client</li>
     *   <li>History Persistence - Record for analytics</li>
     * </ol>
     * 
     * @param query the user's natural language query (must not be null)
     * @param context the orchestration context with user/session info (must not be null)
     * @return the orchestration result (never null)
     * @throws IllegalArgumentException if query or context is null/invalid
     */
    public OrchestrationResult orchestrate(String query, OrchestrationContext context) {
        Objects.requireNonNull(query, ERROR_NULL_QUERY);
        Objects.requireNonNull(context, ERROR_NULL_CONTEXT);

        if (context.getOrchestrationTurnGuard() != null) {
            context.getOrchestrationTurnGuard().verifyBeforeOrchestration();
        }
        
        log.debug("Orchestrating query for user: {}", context.getIdentifier());

        OrchestrationResult result = pipeline.execute(query, context);
        if (context.getOrchestrationTurnGuard() != null) {
            context.getOrchestrationTurnGuard().verifyBeforeResultCommit(result);
        }
        return result;
    }

    /**
     * Orchestrate a trusted structured request without discarding its source or
     * conversation-persistence policy.
     *
     * <p>Hosts use this boundary when they provide a server-approved conversation
     * snapshot and need enrichment without allowing the generic chat-session
     * recorder to become a second transcript authority.</p>
     */
    public OrchestrationResult orchestrate(OrchestrationRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        OrchestrationContext context = Objects.requireNonNull(
            request.orchestrationContext(),
            ERROR_NULL_CONTEXT
        );

        if (context.getOrchestrationTurnGuard() != null) {
            context.getOrchestrationTurnGuard().verifyBeforeOrchestration();
        }

        log.debug("Orchestrating structured request for user: {}", context.getIdentifier());

        OrchestrationResult result = pipeline.execute(request);
        if (context.getOrchestrationTurnGuard() != null) {
            context.getOrchestrationTurnGuard().verifyBeforeResultCommit(result);
        }
        return result;
    }
    
}
