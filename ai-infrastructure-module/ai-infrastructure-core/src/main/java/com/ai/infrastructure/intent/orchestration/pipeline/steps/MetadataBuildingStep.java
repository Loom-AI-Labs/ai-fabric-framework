package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.OrchestrationResultNormalizationProperties;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultDebugSnapshotStore;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTargetSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline step that builds and attaches metadata to the orchestration result.
 * 
 * <p>This step enriches the result with metadata about the request processing,
 * including request ID, session info, intent counts, and authentication status.</p>
 * 
 * <p><strong>Order:</strong> 70 (after intent handling)</p>
 * 
 * <p>The metadata provides context for downstream processing, logging,
 * and debugging. It is attached to the {@link OrchestrationResult}.</p>
 * 
 * @see OrchestrationResult
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetadataBuildingStep implements PipelineStep {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "MetadataBuilding";
    private static final int STEP_ORDER = 70;
    
    // Metadata keys
    private static final String METADATA_KEY_REQUEST_ID = "requestId";
    private static final String METADATA_KEY_SESSION_ID = "sessionId";
    private static final String METADATA_KEY_CONVERSATION_ID = "conversationId";
    private static final String METADATA_KEY_INTENTS_COUNT = "intentsCount";
    private static final String METADATA_KEY_AUTHENTICATED = "authenticated";
    private static final String METADATA_KEY_INTENT_METADATA = "intentMetadata";
    private static final String METADATA_KEY_TARGET_RESOLUTION = "targetResolution";
    private static final String METADATA_KEY_TARGET_RESOLUTION_PINNED_TARGETS = "pinnedTargets";

    private final OrchestrationResultNormalizationProperties normalizationProperties;
    
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
     * Build and attach metadata to the orchestration result.
     * 
     * <p>This step collects information from the pipeline context and
     * intent response to create comprehensive metadata for the result.</p>
     * 
     * @param context the current pipeline context
     * @return updated context (result metadata is set on the intent result)
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Building metadata for request {}", context.getRequestId());
        
        OrchestrationResult result = context.getIntentResult();
        if (result == null) {
            log.warn("No intent result available for metadata building in request {}", 
                context.getRequestId());
            return context;
        }
        
        MultiIntentResponse intentResponse = context.getIntentResponse();
        
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (result.getMetadata() != null && !result.getMetadata().isEmpty()) {
            metadata.putAll(result.getMetadata());
        }
        if (context.getMetadata() != null && !context.getMetadata().isEmpty()) {
            metadata.putAll(context.getMetadata());
        }
        metadata.put(METADATA_KEY_REQUEST_ID, context.getRequestId());
        metadata.put(METADATA_KEY_SESSION_ID, context.getOrchestrationContext().getSessionId());
        if (context.getOrchestrationContext() != null && context.getOrchestrationContext().hasConversation()) {
            metadata.put(METADATA_KEY_CONVERSATION_ID, context.getOrchestrationContext().getConversationId());
        }
        metadata.put(METADATA_KEY_INTENTS_COUNT, 
            intentResponse != null ? intentResponse.getIntents().size() : 0);
        metadata.put(METADATA_KEY_AUTHENTICATED, context.isAuthenticated());
        
        attachIntentMetadata(metadata, intentResponse);

        attachPinnedTargetsTargetResolution(metadata, result);

        result.setMetadata(Collections.unmodifiableMap(metadata));

        if (normalizationProperties != null && normalizationProperties.isDebugSnapshotEnabled()) {
            OrchestrationResultDebugSnapshotStore.record(context.getRequestId(), result);
        }
        
        log.debug("Built metadata with {} entries for request {}", 
            metadata.size(), context.getRequestId());
        
        return context.toBuilder()
            .metadata(metadata)
            .build();
    }

    private void attachIntentMetadata(Map<String, Object> metadata, MultiIntentResponse intentResponse) {
        if (metadata == null || intentResponse == null) {
            return;
        }

        boolean requiresTargetResolution = intentResponse.getIntents() != null
            && intentResponse.getIntents().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(intent -> Boolean.TRUE.equals(intent.getRequiresTargetResolution()));

        Map<String, Object> enriched = new LinkedHashMap<>();
        if (!CollectionUtils.isEmpty(intentResponse.getMetadata())) {
            enriched.putAll(intentResponse.getMetadata());
        }
        enriched.put("requiresTargetResolution", requiresTargetResolution);

        metadata.put(METADATA_KEY_INTENT_METADATA, Collections.unmodifiableMap(enriched));
    }

    private void attachPinnedTargetsTargetResolution(Map<String, Object> metadata, OrchestrationResult result) {
        if (metadata == null || result == null) {
            return;
        }
        if (result.getType() != com.ai.infrastructure.intent.orchestration.OrchestrationResultType.ACTION_EXECUTED) {
            return;
        }
        if (result.getData() == null || result.getData().isEmpty()) {
            return;
        }

        Object raw = result.getData().get("actionResult");
        if (!(raw instanceof ActionResult actionResult)) {
            return;
        }
        List<com.ai.infrastructure.intent.action.ActionTargetRef> pinned = actionResult.getPinnedTargets();
        if (pinned == null || pinned.isEmpty()) {
            return;
        }

        Map<String, Object> pinnedMeta = Map.of(
            "source", ResolvedTargetSource.ACTION_RESULT_ITEMS.name(),
            "count", pinned.size()
        );

        Object existing = metadata.get(METADATA_KEY_TARGET_RESOLUTION);
        if (!(existing instanceof Map<?, ?> existingMap) || existingMap.isEmpty()) {
            metadata.put(METADATA_KEY_TARGET_RESOLUTION, pinnedMeta);
            return;
        }

        LinkedHashMap<String, Object> merged = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : existingMap.entrySet()) {
            if (entry == null || entry.getKey() == null) {
                continue;
            }
            merged.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        merged.put(METADATA_KEY_TARGET_RESOLUTION_PINNED_TARGETS, pinnedMeta);
        metadata.put(METADATA_KEY_TARGET_RESOLUTION, Collections.unmodifiableMap(merged));
    }
}
