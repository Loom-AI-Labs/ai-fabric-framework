package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class InformationIntentPlanningSupport {

    private static final String METADATA_KEY_SOURCE = "source";
    private static final String METADATA_KEY_USER_ID = "userId";
    private static final String METADATA_KEY_SESSION_ID = "sessionId";
    private static final String METADATA_KEY_AUTHENTICATED = "authenticated";
    private static final String METADATA_KEY_OPTIMIZED_QUERY = "optimizedQuery";
    private static final String METADATA_VALUE_ORCHESTRATOR = "orchestrator";
    private static final String DATA_KEY_REQUIRES_GENERATION = "requiresGeneration";

    private InformationIntentPlanningSupport() {
    }

    static Plan plan(Intent intent,
                     OrchestrationContext context,
                     PipelineContext pipelineContext,
                     OrchestrationProperties orchestrationProperties,
                     boolean deterministic) {
        OrchestrationPolicy policy = pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null;
        OrchestrationPolicy.OrchestrationCapabilities capabilities = policy != null ? policy.capabilities() : null;
        boolean retrievalEnabled = policy == null
            || policy.capabilities() == null
            || policy.capabilities().retrievalEnabled();
        OrchestrationPolicy.RagBudgets ragBudgets = policy != null ? policy.ragBudgets() : null;
        boolean deepRetrievalEnabled = capabilities != null && capabilities.deepRetrievalEnabled();
        boolean retrievalAllowlistRequired = capabilities != null && capabilities.retrievalAllowlistRequired();
        boolean vectorSpaceSelectionRequired = capabilities != null && capabilities.vectorSpaceSelectionRequired();
        boolean fanoutAllowed = ragBudgets == null
            || ragBudgets.fanoutEnabled() == null
            || Boolean.TRUE.equals(ragBudgets.fanoutEnabled());

        boolean requiresRetrieval = intent.requiresRetrievalOrDefault(true);
        boolean llmRequiresGeneration = intent.requiresGenerationOrDefault(false);
        boolean minimizeRagHeuristicEnabled = capabilities == null || capabilities.minimizeRagWhenPinnedTargetsCoverRequest();

        boolean forceRetrievalWhenTargetsPresent = capabilities != null && capabilities.forceRetrievalWhenTargetsPresent();
        boolean forceRetrievalConsiderStoredTargets = capabilities != null && capabilities.forceRetrievalConsiderStoredTargets();

        boolean ackLike = !requiresRetrieval
            && !llmRequiresGeneration
            && StringUtils.hasText(intent.getDirectAnswer());

        boolean retrievalForced = false;
        String retrievalForcedReason = null;

        if (!requiresRetrieval
            && retrievalEnabled
            && forceRetrievalWhenTargetsPresent
            && !ackLike
            && pipelineContext != null
            && pipelineContext.getResolvedTargets() != null
            && !pipelineContext.getResolvedTargets().isEmpty()) {

            List<ResolvedTarget> targets = pipelineContext.getResolvedTargets();
            boolean hasActiveTargets = targets.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(target -> target.getSource() == ResolvedTargetSource.REQUEST_ATTACHMENTS);

            boolean hasStoredTargets = forceRetrievalConsiderStoredTargets && targets.stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(target -> target.getSource() == ResolvedTargetSource.SESSION_METADATA
                    || target.getSource() == ResolvedTargetSource.WORKING_SET);

            if (hasActiveTargets || hasStoredTargets) {
                requiresRetrieval = true;
                retrievalForced = true;
                retrievalForcedReason = hasActiveTargets ? "ACTIVE_TARGETS" : "STORED_TARGETS";
            }
        }

        boolean skippedRetrievalForPinnedTargets = deterministic
            && minimizeRagHeuristicEnabled
            && requiresRetrieval
            && RagContextSupport.shouldSkipRetrievalForPinnedTargets(intent, pipelineContext);
        if (skippedRetrievalForPinnedTargets) {
            requiresRetrieval = false;
        }

        boolean needsGeneration = skippedRetrievalForPinnedTargets
            ? true
            : (requiresRetrieval ? (deterministic || llmRequiresGeneration) : llmRequiresGeneration);
        if (requiresRetrieval
            && !needsGeneration
            && orchestrationProperties != null
            && orchestrationProperties.isAlwaysGenerateInformation()) {
            needsGeneration = true;
        }

        String optimizedQuery = StringUtils.hasText(intent.getOptimizedQuery()) ? intent.getOptimizedQuery() : null;
        String processedQuery = pipelineContext != null ? pipelineContext.getEffectiveQuery() : null;
        String retrievalBaseQuery = StringUtils.hasText(optimizedQuery)
            ? optimizedQuery
            : (StringUtils.hasText(processedQuery) ? processedQuery : intent.getIntentOrAction());
        String generationQuery = StringUtils.hasText(processedQuery) ? processedQuery : retrievalBaseQuery;

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_KEY_SOURCE, METADATA_VALUE_ORCHESTRATOR);
        metadata.put(METADATA_KEY_USER_ID, context.getIdentifier());
        metadata.put(METADATA_KEY_SESSION_ID, context.getSessionId());
        metadata.put(METADATA_KEY_AUTHENTICATED, context.isAuthenticated());
        metadata.put(DATA_KEY_REQUIRES_GENERATION, needsGeneration);
        metadata.put("requiresRetrieval", requiresRetrieval);
        metadata.put("minimizeRagHeuristicEnabled", minimizeRagHeuristicEnabled);
        if (forceRetrievalWhenTargetsPresent) {
            metadata.put("forceRetrievalWhenTargetsPresentEnabled", true);
            metadata.put("forceRetrievalConsiderStoredTargetsEnabled", forceRetrievalConsiderStoredTargets);
        }
        if (retrievalForced) {
            metadata.put("retrievalForced", true);
            metadata.put("retrievalForcedReason", retrievalForcedReason);
        }
        if (skippedRetrievalForPinnedTargets) {
            metadata.put("retrievalSkipped", true);
            metadata.put("retrievalSkipReason", "PINNED_TARGETS");
        }
        if (optimizedQuery != null) {
            metadata.put(METADATA_KEY_OPTIMIZED_QUERY, optimizedQuery);
        }
        if (pipelineContext != null && !pipelineContext.getDetectedPiiTypesView().isEmpty()) {
            metadata.put("piiProcessed", true);
            metadata.put("piiDetectedTypes", pipelineContext.getDetectedPiiTypesView());
        }

        return new Plan(
            policy,
            capabilities,
            retrievalEnabled,
            ragBudgets,
            deepRetrievalEnabled,
            retrievalAllowlistRequired,
            vectorSpaceSelectionRequired,
            fanoutAllowed,
            deterministic,
            requiresRetrieval,
            llmRequiresGeneration,
            needsGeneration,
            minimizeRagHeuristicEnabled,
            forceRetrievalWhenTargetsPresent,
            forceRetrievalConsiderStoredTargets,
            retrievalForced,
            retrievalForcedReason,
            skippedRetrievalForPinnedTargets,
            optimizedQuery,
            processedQuery,
            retrievalBaseQuery,
            generationQuery,
            metadata
        );
    }

    record Plan(
        OrchestrationPolicy policy,
        OrchestrationPolicy.OrchestrationCapabilities capabilities,
        boolean retrievalEnabled,
        OrchestrationPolicy.RagBudgets ragBudgets,
        boolean deepRetrievalEnabled,
        boolean retrievalAllowlistRequired,
        boolean vectorSpaceSelectionRequired,
        boolean fanoutAllowed,
        boolean deterministic,
        boolean requiresRetrieval,
        boolean llmRequiresGeneration,
        boolean needsGeneration,
        boolean minimizeRagHeuristicEnabled,
        boolean forceRetrievalWhenTargetsPresent,
        boolean forceRetrievalConsiderStoredTargets,
        boolean retrievalForced,
        String retrievalForcedReason,
        boolean skippedRetrievalForPinnedTargets,
        String optimizedQuery,
        String processedQuery,
        String retrievalBaseQuery,
        String generationQuery,
        Map<String, Object> metadata
    ) {
    }
}
