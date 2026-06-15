package ai.fabric.intent.orchestration.policy;

import ai.fabric.config.OrchestrationProperties;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Server-authoritative orchestration policy resolved per request.
 *
 * <p>This is an internal contract used by pipeline steps to avoid scattered flag reads and
 * to enable position/mode driven orchestration over time.</p>
 */
public record OrchestrationPolicy(
    OrchestrationProfile profile,
    String mode,
    String position,
    OrchestrationProperties.InformationMode informationMode,
    OrchestrationCapabilities capabilities,
    ReadActionResolutionPolicy readActionResolutionPolicy,
    RagBudgets ragBudgets,
    ResponseGenerationBudgets responseGenerationBudgets
) {

    public OrchestrationPolicy(OrchestrationProfile profile,
                               String mode,
                               String position,
                               OrchestrationProperties.InformationMode informationMode,
                               OrchestrationCapabilities capabilities,
                               RagBudgets ragBudgets) {
        this(profile, mode, position, informationMode, capabilities, null, ragBudgets, null);
    }

    public OrchestrationPolicy(OrchestrationProfile profile,
                               String mode,
                               String position,
                               OrchestrationProperties.InformationMode informationMode,
                               OrchestrationCapabilities capabilities,
                               ReadActionResolutionPolicy readActionResolutionPolicy,
                               RagBudgets ragBudgets) {
        this(profile, mode, position, informationMode, capabilities, readActionResolutionPolicy, ragBudgets, null);
    }

    public OrchestrationPolicy(OrchestrationProfile profile,
                               String mode,
                               String position,
                               OrchestrationProperties.InformationMode informationMode,
                               OrchestrationCapabilities capabilities,
                               RagBudgets ragBudgets,
                               ResponseGenerationBudgets responseGenerationBudgets) {
        this(profile, mode, position, informationMode, capabilities, null, ragBudgets, responseGenerationBudgets);
    }

    public OrchestrationPolicy {
        profile = profile != null ? profile : OrchestrationProfile.DEFAULT;
        mode = normalize(mode);
        position = normalize(position);
        informationMode = informationMode != null ? informationMode : profile.defaultInformationMode();
        capabilities = capabilities != null ? capabilities : OrchestrationCapabilities.defaults();
        readActionResolutionPolicy = readActionResolutionPolicy != null
            ? readActionResolutionPolicy
            : ReadActionResolutionPolicy.defaults();
        ragBudgets = ragBudgets != null ? ragBudgets : RagBudgets.defaults();
        responseGenerationBudgets = responseGenerationBudgets != null
            ? responseGenerationBudgets
            : ResponseGenerationBudgets.defaults();
    }

    private static String normalize(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record OrchestrationCapabilities(
        boolean actionsEnabled,
        boolean retrievalEnabled,
        boolean deepRetrievalEnabled,
        boolean suggestionsEnabled,
        boolean exposeReadProbeFallbackAttempt,
        boolean actionsPreferred,
        boolean knowledgeBaseOverviewEnabled,
        boolean retrievalAllowlistRequired,
        boolean vectorSpaceSelectionRequired,
        boolean minimizeRagWhenPinnedTargetsCoverRequest,
        boolean forceRetrievalWhenTargetsPresent,
        boolean forceRetrievalConsiderStoredTargets,
        boolean forceGroundingEligibleReadActionPostGeneration
    ) {
        public OrchestrationCapabilities(boolean actionsEnabled,
                                         boolean retrievalEnabled,
                                         boolean deepRetrievalEnabled,
                                         boolean suggestionsEnabled,
                                         boolean exposeReadProbeFallbackAttempt,
                                         boolean actionsPreferred,
                                         boolean knowledgeBaseOverviewEnabled,
                                         boolean retrievalAllowlistRequired,
                                         boolean vectorSpaceSelectionRequired,
                                         boolean minimizeRagWhenPinnedTargetsCoverRequest,
                                         boolean forceRetrievalWhenTargetsPresent,
                                         boolean forceRetrievalConsiderStoredTargets) {
            this(
                actionsEnabled,
                retrievalEnabled,
                deepRetrievalEnabled,
                suggestionsEnabled,
                exposeReadProbeFallbackAttempt,
                actionsPreferred,
                knowledgeBaseOverviewEnabled,
                retrievalAllowlistRequired,
                vectorSpaceSelectionRequired,
                minimizeRagWhenPinnedTargetsCoverRequest,
                forceRetrievalWhenTargetsPresent,
                forceRetrievalConsiderStoredTargets,
                false
            );
        }

        public static OrchestrationCapabilities defaults() {
            return new OrchestrationCapabilities(true, true, false, true, false, false, true, false, false, true, false, false, false);
        }
    }

    public record RagBudgets(
        Boolean fanoutEnabled,
        Integer maxSpaces,
        Integer topKPerSpace,
        Integer maxDocumentsReturnedToClient,
        Integer maxDocumentsUsedForContext,
        Integer maxContextChars,
        List<String> retrievalVectorSpacesAllowlist,
        Double similarityThreshold
    ) {
        public RagBudgets(Boolean fanoutEnabled,
                          Integer maxSpaces,
                          Integer topKPerSpace,
                          Integer maxDocumentsReturnedToClient,
                          Integer maxDocumentsUsedForContext,
                          Integer maxContextChars,
                          List<String> retrievalVectorSpacesAllowlist) {
            this(
                fanoutEnabled,
                maxSpaces,
                topKPerSpace,
                maxDocumentsReturnedToClient,
                maxDocumentsUsedForContext,
                maxContextChars,
                retrievalVectorSpacesAllowlist,
                null
            );
        }

        public RagBudgets {
            retrievalVectorSpacesAllowlist = normalizeAllowlist(retrievalVectorSpacesAllowlist);
            similarityThreshold = normalizeSimilarityThreshold(similarityThreshold);
        }

        public static RagBudgets defaults() {
            return new RagBudgets(null, null, null, null, null, null, List.of(), null);
        }

        public boolean hasVectorSpaceAllowlist() {
            return retrievalVectorSpacesAllowlist != null && !retrievalVectorSpacesAllowlist.isEmpty();
        }

        private static List<String> normalizeAllowlist(List<String> input) {
            if (input == null || input.isEmpty()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (String value : input) {
                if (!StringUtils.hasText(value)) {
                    continue;
                }
                String normalized = value.trim().toLowerCase(Locale.ROOT);
                if (normalized.isEmpty()) {
                    continue;
                }
                if (!out.contains(normalized)) {
                    out.add(normalized);
                }
            }
            return out.isEmpty() ? List.of() : Collections.unmodifiableList(out);
        }

        private static Double normalizeSimilarityThreshold(Double input) {
            if (input == null || !Double.isFinite(input) || input < 0.0d || input > 1.0d) {
                return null;
            }
            return input;
        }
    }

    public record ReadActionResolutionPolicy(
        boolean enabled,
        OrchestrationProperties.ReadActionResolutionPlanningMode planningMode,
        List<String> allowedReadActions,
        boolean requireAllowlist,
        int maxIterations,
        int maxActionsPerIteration,
        int maxTotalActions,
        int maxParallelActions,
        int maxPlannerContextChars,
        int maxActionEvidenceCharsPerAction,
        OrchestrationProperties.ReadActionResolutionRagCooperationMode ragCooperationMode,
        boolean requireGroundingEligible
    ) {
        public ReadActionResolutionPolicy {
            planningMode = planningMode != null
                ? planningMode
                : OrchestrationProperties.ReadActionResolutionPlanningMode.OFF;
            allowedReadActions = RagBudgets.normalizeAllowlist(allowedReadActions);
            maxIterations = normalizePositive(maxIterations, 1);
            maxActionsPerIteration = normalizePositive(maxActionsPerIteration, 2);
            maxTotalActions = normalizePositive(maxTotalActions, maxActionsPerIteration);
            maxParallelActions = normalizePositive(maxParallelActions, 1);
            maxPlannerContextChars = normalizePositive(maxPlannerContextChars, 4_000);
            maxActionEvidenceCharsPerAction = normalizePositive(maxActionEvidenceCharsPerAction, 2_400);
            ragCooperationMode = ragCooperationMode != null
                ? ragCooperationMode
                : OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT;
            if (!enabled || planningMode == OrchestrationProperties.ReadActionResolutionPlanningMode.OFF) {
                enabled = false;
                planningMode = OrchestrationProperties.ReadActionResolutionPlanningMode.OFF;
            }
        }

        public static ReadActionResolutionPolicy defaults() {
            return new ReadActionResolutionPolicy(
                false,
                OrchestrationProperties.ReadActionResolutionPlanningMode.OFF,
                List.of(),
                false,
                1,
                2,
                2,
                1,
                4_000,
                2_400,
                OrchestrationProperties.ReadActionResolutionRagCooperationMode.RAG_IF_ACTIONS_INSUFFICIENT,
                true
            );
        }

        public boolean hasAllowedReadActions() {
            return allowedReadActions != null && !allowedReadActions.isEmpty();
        }

        private static int normalizePositive(int value, int fallback) {
            return value > 0 ? value : fallback;
        }
    }

    public record ResponseGenerationBudgets(
        Integer conciseMaxTokens,
        Integer standardMaxTokens,
        Integer deepMaxTokens
    ) {
        public ResponseGenerationBudgets {
            conciseMaxTokens = normalizePositiveInteger(conciseMaxTokens);
            standardMaxTokens = normalizePositiveInteger(standardMaxTokens);
            deepMaxTokens = normalizePositiveInteger(deepMaxTokens);
        }

        public static ResponseGenerationBudgets defaults() {
            return new ResponseGenerationBudgets(null, null, null);
        }

        private static Integer normalizePositiveInteger(Integer value) {
            return value != null && value > 0 ? value : null;
        }
    }
}
