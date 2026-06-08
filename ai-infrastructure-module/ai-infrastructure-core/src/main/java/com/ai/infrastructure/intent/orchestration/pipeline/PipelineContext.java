package com.ai.infrastructure.intent.orchestration.pipeline;

import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.AIChatMessage;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTarget;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable carrier of data accumulated during pipeline orchestration.
 * 
 * <p>The {@code PipelineContext} flows through all {@link PipelineStep}s, carrying:</p>
 * <ul>
 *   <li><strong>Input data:</strong> Original query, orchestration context, request metadata</li>
 *   <li><strong>Accumulated state:</strong> Processed query, detected PII types, intent responses</li>
 *   <li><strong>Control flow:</strong> Termination signals for early exit</li>
 * </ul>
 * 
 * <p><strong>Immutability:</strong> This class uses a builder pattern with {@code toBuilder()}
 * to create modified copies. Steps should never mutate the context directly; instead, use
 * the builder to create a new instance with updated fields.</p>
 * 
 * <p><strong>Example Usage:</strong></p>
 * <pre>{@code
 * // Create initial context
 * PipelineContext ctx = PipelineContext.from(query, orchestrationContext);
 * 
 * // Update with new data
 * ctx = ctx.toBuilder()
 *     .processedQuery(redactedQuery)
 *     .detectedPiiTypes(piiTypes)
 *     .build();
 * 
 * // Terminate pipeline early
 * ctx = ctx.terminate(OrchestrationResult.error("Access denied"));
 * }</pre>
 * 
 * @see Pipeline
 * @see PipelineStep
 * @since 1.0
 */
@Data
@Builder(toBuilder = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class PipelineContext {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String REQUEST_ID_PREFIX = "rag-";
    
    // =========================================================================
    // Input Fields (set at creation)
    // =========================================================================
    
    /**
     * The original user query before any processing.
     */
    private final String originalQuery;
    
    /**
     * The orchestration context containing user/session information.
     */
    private final OrchestrationContext orchestrationContext;
    
    /**
     * Timestamp when the request was received.
     */
    private final LocalDateTime requestTimestamp;
    
    /**
     * Unique identifier for this request (for tracing and logging).
     */
    private final String requestId;
    
    // =========================================================================
    // Accumulated State (updated by steps)
    // =========================================================================
    
    /**
     * The query after PII redaction and other processing.
     */
    @Builder.Default
    private final String processedQuery = null;
    
    /**
     * List of PII types detected in the input query.
     */
    @Builder.Default
    private final List<String> detectedPiiTypes = new ArrayList<>();
    
    /**
     * The multi-intent response from intent extraction.
     */
    private final MultiIntentResponse intentResponse;
    
    /**
     * The result from intent handling.
     */
    private final OrchestrationResult intentResult;
    
    /**
     * Accumulated metadata from various steps.
     */
    @Builder.Default
    private final Map<String, Object> metadata = new LinkedHashMap<>();
    
    /**
     * Smart suggestion data (if generated).
     */
    @Builder.Default
    private final Map<String, Object> smartSuggestion = new LinkedHashMap<>();

    /**
     * Server-resolved orchestration policy for this request.
     */
    private final OrchestrationPolicy orchestrationPolicy;

    /**
     * Set of action names that have been explicitly confirmed for this request.
     *
     * <p>This is internal pipeline state (not user-supplied) used to prevent re-confirmation loops.</p>
     */
    @Builder.Default
    private final Set<String> confirmedActions = Set.of();

    /**
     * Deterministically resolved targets for this request (attachments / working set).
     */
    @Builder.Default
    private final List<ResolvedTarget> resolvedTargets = new ArrayList<>();

    /**
     * Structured conversation history messages for provider-native multi-message prompting.
     */
    @Builder.Default
    private final List<AIChatMessage> historyMessages = new ArrayList<>();

    /**
     * Rendered pinned targets context (attachments / selected UI cards) to be injected into the
     * current user message for LLM calls.
     *
     * <p>Must never be used as a vector/embedding query input.</p>
     */
    private final String pinnedTargetsContext;

    /**
     * The sanitized payload for the response.
     */
    @Builder.Default
    private final Map<String, Object> sanitizedPayload = new LinkedHashMap<>();
    
    // =========================================================================
    // Control Flow Fields
    // =========================================================================
    
    /**
     * Flag indicating the pipeline should terminate early.
     */
    @Builder.Default
    private final boolean shouldTerminate = false;
    
    /**
     * The result to return if pipeline is terminated early.
     */
    private final OrchestrationResult earlyTerminationResult;
    
    // =========================================================================
    // Factory Methods
    // =========================================================================
    
    /**
     * Create an initial pipeline context from a query and orchestration context.
     * 
     * <p>This is the primary entry point for creating a new context at the
     * start of pipeline execution.</p>
     * 
     * @param query the user's query (must not be null)
     * @param context the orchestration context (must not be null, must be valid)
     * @return a new PipelineContext initialized for processing
     * @throws NullPointerException if query or context is null
     * @throws IllegalArgumentException if context validation fails
     */
    public static PipelineContext from(String query, OrchestrationContext context) {
        Objects.requireNonNull(query, "query must not be null");
        Objects.requireNonNull(context, "context must not be null");
        context.validate();
        
        String requestId = context.getOrGenerateRequestId();
        if (requestId == null || requestId.isBlank()) {
            requestId = REQUEST_ID_PREFIX + UUID.randomUUID();
        }
        
        return PipelineContext.builder()
            .originalQuery(query)
            .orchestrationContext(context)
            .requestId(requestId)
            .requestTimestamp(LocalDateTime.now())
            .processedQuery(query) // Initially same as original
            .detectedPiiTypes(new ArrayList<>())
            .metadata(new LinkedHashMap<>())
            .smartSuggestion(new LinkedHashMap<>())
            .sanitizedPayload(new LinkedHashMap<>())
            .shouldTerminate(false)
            .build();
    }
    
    // =========================================================================
    // Control Flow Methods
    // =========================================================================
    
    /**
     * Create a new context that signals pipeline termination with the given result.
     * 
     * <p>Use this method when a step needs to terminate the pipeline early
     * (e.g., security block, access denied, compliance failure).</p>
     * 
     * <p>After calling this method, subsequent steps will be skipped (per the
     * default {@link PipelineStep#shouldSkip(PipelineContext)} implementation).</p>
     * 
     * @param result the result to return from the pipeline (must not be null)
     * @return a new context with termination flag set
     * @throws NullPointerException if result is null
     */
    public PipelineContext terminate(OrchestrationResult result) {
        Objects.requireNonNull(result, "termination result must not be null");
        return this.toBuilder()
            .shouldTerminate(true)
            .earlyTerminationResult(result)
            .intentResult(result)
            .build();
    }
    
    // =========================================================================
    // Convenience Methods
    // =========================================================================
    
    /**
     * Get the user identifier from the orchestration context.
     * 
     * @return the user identifier (userId for authenticated, sessionId for anonymous)
     */
    public String getIdentifier() {
        return orchestrationContext != null ? orchestrationContext.getIdentifier() : null;
    }
    
    /**
     * Check if the current user is authenticated.
     * 
     * @return true if authenticated, false if anonymous
     */
    public boolean isAuthenticated() {
        return orchestrationContext != null && orchestrationContext.isAuthenticated();
    }
    
    /**
     * Get the effective query to use (processed if available, original otherwise).
     * 
     * @return the query to use for processing
     */
    public String getEffectiveQuery() {
        return processedQuery != null && !processedQuery.isBlank() 
            ? processedQuery 
            : originalQuery;
    }

    /**
     * True when the given action has already been explicitly confirmed in the current pipeline execution.
     */
    public boolean isActionConfirmed(String actionName) {
        if (actionName == null || actionName.isBlank()) {
            return false;
        }
        return confirmedActions != null && confirmedActions.contains(actionName.trim());
    }
    
    /**
     * Add a key-value pair to the metadata map.
     * 
     * <p>Returns a new context with the updated metadata (immutable pattern).</p>
     * 
     * @param key the metadata key
     * @param value the metadata value
     * @return a new context with the added metadata
     */
    public PipelineContext withMetadata(String key, Object value) {
        Map<String, Object> newMetadata = new LinkedHashMap<>(this.metadata);
        newMetadata.put(key, value);
        return this.toBuilder()
            .metadata(newMetadata)
            .build();
    }
    
    /**
     * Get an unmodifiable view of the metadata.
     * 
     * @return unmodifiable metadata map
     */
    public Map<String, Object> getMetadataView() {
        return Collections.unmodifiableMap(metadata);
    }
    
    /**
     * Get an unmodifiable view of detected PII types.
     * 
     * @return unmodifiable list of PII types
     */
    public List<String> getDetectedPiiTypesView() {
        return Collections.unmodifiableList(detectedPiiTypes);
    }
}
