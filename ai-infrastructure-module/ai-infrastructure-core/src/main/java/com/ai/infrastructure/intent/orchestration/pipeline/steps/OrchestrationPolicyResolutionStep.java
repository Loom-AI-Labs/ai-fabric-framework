package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationContextMetadataKeys;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Resolves the server-authoritative {@link OrchestrationPolicy} for the current request.
 *
 * <p><strong>Order:</strong> 22 (after AccessControlStep (20), before ConversationEnrichmentStep (25)).</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrchestrationPolicyResolutionStep implements PipelineStep {

    private static final String STEP_NAME = "OrchestrationPolicyResolution";
    private static final int STEP_ORDER = 22;

    private static final String METADATA_KEY_POLICY = "orchestrationPolicy";

    private static final String ERROR_UNSUPPORTED_MODE_PREFIX = "Unsupported mode: ";
    private static final String ERROR_UNSUPPORTED_DEFAULT_MODE_PREFIX = "Unsupported default mode: ";

    private final OrchestrationProperties orchestrationProperties;

    @Override
    public String getStepName() {
        return STEP_NAME;
    }

    @Override
    public int getOrder() {
        return STEP_ORDER;
    }

    @Override
    public boolean shouldSkip(PipelineContext context) {
        return context == null || context.isShouldTerminate();
    }

    @Override
    public PipelineContext process(PipelineContext context) {
        Objects.requireNonNull(context, "context must not be null");
        OrchestrationContext orchestrationContext = context.getOrchestrationContext();

        OrchestrationProfile profile = orchestrationProperties != null && orchestrationProperties.getProfile() != null
            ? orchestrationProperties.getProfile()
            : OrchestrationProfile.DEFAULT;

        String requestedPosition = orchestrationContext != null ? orchestrationContext.getPosition() : null;
        String requestedMode = orchestrationContext != null ? orchestrationContext.getMode() : null;
        String normalizedPosition = normalizeKey(requestedPosition);
        String normalizedRequestedMode = normalizeKey(requestedMode);

        String modeSource = null;
        String effectiveMode = null;

        if (effectiveMode == null && normalizedRequestedMode != null) {
            OrchestrationProperties.ModeOverrides modeOverrides = lookupCaseInsensitive(
                orchestrationProperties != null ? orchestrationProperties.getModes() : null,
                normalizedRequestedMode
            );
            if (modeOverrides != null) {
                effectiveMode = normalizedRequestedMode;
                modeSource = "REQUEST_MODE";
            } else if (orchestrationProperties != null && orchestrationProperties.isStrictModeRouting()) {
                return context.terminate(OrchestrationResult.error(ERROR_UNSUPPORTED_MODE_PREFIX + normalizedRequestedMode));
            }
        }

        if (effectiveMode == null) {
            String normalizedDefaultMode = normalizeKey(orchestrationProperties != null ? orchestrationProperties.getDefaultMode() : null);
            if (normalizedDefaultMode != null) {
                OrchestrationProperties.ModeOverrides defaultOverrides = lookupCaseInsensitive(
                    orchestrationProperties != null ? orchestrationProperties.getModes() : null,
                    normalizedDefaultMode
                );
                if (defaultOverrides != null) {
                    effectiveMode = normalizedDefaultMode;
                    modeSource = "DEFAULT_MODE";
                } else {
                    return context.terminate(OrchestrationResult.error(ERROR_UNSUPPORTED_DEFAULT_MODE_PREFIX + normalizedDefaultMode));
                }
            }
        }

        OrchestrationProperties.ModeOverrides effectiveModeOverrides = effectiveMode != null
            ? lookupCaseInsensitive(orchestrationProperties != null ? orchestrationProperties.getModes() : null, effectiveMode)
            : null;

        OrchestrationProperties.InformationMode informationModeEffective = profile.defaultInformationMode();
        Boolean advancedRagOverride = null;

        if (effectiveModeOverrides != null) {
            // Capability bundle (mode-level gating).
            // Defaults preserve existing behavior when unset (greenfield-compatible, but non-breaking).
            if (effectiveModeOverrides.getInformationMode() != null) {
                informationModeEffective = effectiveModeOverrides.getInformationMode();
            }
            if (effectiveModeOverrides.getUseAdvancedRag() != null) {
                advancedRagOverride = effectiveModeOverrides.getUseAdvancedRag();
            }
        }

        if (orchestrationProperties != null && orchestrationProperties.getInformationMode() != null) {
            informationModeEffective = orchestrationProperties.getInformationMode();
        }

        boolean actionsEnabled = effectiveModeOverrides != null && effectiveModeOverrides.getActionsEnabled() != null
            ? effectiveModeOverrides.getActionsEnabled()
            : true;
        boolean retrievalEnabled = effectiveModeOverrides != null && effectiveModeOverrides.getRetrievalEnabled() != null
            ? effectiveModeOverrides.getRetrievalEnabled()
            : true;
        boolean deepRetrievalEnabled = effectiveModeOverrides != null && effectiveModeOverrides.getDeepRetrievalEnabled() != null
            ? effectiveModeOverrides.getDeepRetrievalEnabled()
            : false;

        boolean forceRetrievalWhenTargetsPresent = effectiveModeOverrides != null && effectiveModeOverrides.getForceRetrievalWhenTargetsPresent() != null
            ? effectiveModeOverrides.getForceRetrievalWhenTargetsPresent()
            : false;

        boolean forceRetrievalConsiderStoredTargets = effectiveModeOverrides != null && effectiveModeOverrides.getForceRetrievalConsiderStoredTargets() != null
            ? effectiveModeOverrides.getForceRetrievalConsiderStoredTargets()
            : false;

        boolean minimizeRagWhenPinnedTargetsCoverRequest = effectiveModeOverrides != null && effectiveModeOverrides.getMinimizeRagWhenPinnedTargetsCoverRequest() != null
            ? effectiveModeOverrides.getMinimizeRagWhenPinnedTargetsCoverRequest()
            : true;
        boolean suggestionsEnabled = effectiveModeOverrides != null && effectiveModeOverrides.getSuggestionsEnabled() != null
            ? effectiveModeOverrides.getSuggestionsEnabled()
            : true;
        String suggestionsEnabledSource = effectiveModeOverrides != null && effectiveModeOverrides.getSuggestionsEnabled() != null
            ? "MODE"
            : "DEFAULT";

        boolean actionsPreferred = effectiveModeOverrides != null && effectiveModeOverrides.getActionsPreferred() != null
            ? effectiveModeOverrides.getActionsPreferred()
            : false;

        boolean knowledgeBaseOverviewEnabled = effectiveModeOverrides != null && effectiveModeOverrides.getKnowledgeBaseOverviewEnabled() != null
            ? effectiveModeOverrides.getKnowledgeBaseOverviewEnabled()
            : true;

        boolean retrievalAllowlistRequired = effectiveModeOverrides != null && effectiveModeOverrides.getRetrievalAllowlistRequired() != null
            ? effectiveModeOverrides.getRetrievalAllowlistRequired()
            : false;

        boolean vectorSpaceSelectionRequired = effectiveModeOverrides != null && effectiveModeOverrides.getVectorSpaceSelectionRequired() != null
            ? effectiveModeOverrides.getVectorSpaceSelectionRequired()
            : false;

        boolean exposeReadProbeFallbackAttempt = orchestrationProperties != null
            && orchestrationProperties.isExposeReadProbeFallbackAttempt();
        if (effectiveModeOverrides != null && effectiveModeOverrides.getExposeReadProbeFallbackAttempt() != null) {
            exposeReadProbeFallbackAttempt = effectiveModeOverrides.getExposeReadProbeFallbackAttempt();
        }

        boolean forceGroundingEligibleReadActionPostGeneration = effectiveModeOverrides != null
            && Boolean.TRUE.equals(effectiveModeOverrides.getForceGroundingEligibleReadActionPostGeneration());

        OrchestrationPolicy.RagBudgets ragBudgets = null;
        if (effectiveModeOverrides != null && effectiveModeOverrides.getRag() != null) {
            OrchestrationProperties.RagModeOverrides rag = effectiveModeOverrides.getRag();
            ragBudgets = new OrchestrationPolicy.RagBudgets(
                rag.getFanoutEnabled(),
                rag.getMaxSpaces(),
                rag.getTopKPerSpace(),
                rag.getMaxDocumentsReturnedToClient(),
                rag.getMaxDocumentsUsedForContext(),
                rag.getMaxContextChars(),
                rag.getRetrievalVectorSpacesAllowlist(),
                rag.getSimilarityThreshold()
            );
        }

        OrchestrationPolicy.ReadActionResolutionPolicy readActionResolutionPolicy = null;
        if (effectiveModeOverrides != null && effectiveModeOverrides.getReadActionResolution() != null) {
            OrchestrationProperties.ReadActionResolutionModeOverrides read = effectiveModeOverrides.getReadActionResolution();
            readActionResolutionPolicy = new OrchestrationPolicy.ReadActionResolutionPolicy(
                Boolean.TRUE.equals(read.getEnabled()),
                read.getPlanningMode(),
                read.getAllowedReadActions(),
                Boolean.TRUE.equals(read.getRequireAllowlist()),
                read.getMaxIterations() != null ? read.getMaxIterations() : 1,
                read.getMaxActionsPerIteration() != null ? read.getMaxActionsPerIteration() : 2,
                read.getMaxTotalActions() != null ? read.getMaxTotalActions() : 2,
                read.getMaxParallelActions() != null ? read.getMaxParallelActions() : 1,
                read.getMaxPlannerContextChars() != null ? read.getMaxPlannerContextChars() : 4_000,
                read.getMaxActionEvidenceCharsPerAction() != null ? read.getMaxActionEvidenceCharsPerAction() : 2_400,
                read.getRagCooperationMode(),
                read.getRequireGroundingEligible() == null || read.getRequireGroundingEligible()
            );
        }

        Double deploymentRagSimilarityThreshold = readSimilarityThresholdOverride(orchestrationContext);
        if (deploymentRagSimilarityThreshold != null) {
            ragBudgets = mergeSimilarityThreshold(ragBudgets, deploymentRagSimilarityThreshold);
        }
        Integer deploymentRagMaxDocumentsUsedForContext = readPositiveIntegerOverride(
            orchestrationContext,
            OrchestrationContextMetadataKeys.RAG_MAX_DOCUMENTS_USED_FOR_CONTEXT
        );
        if (deploymentRagMaxDocumentsUsedForContext != null) {
            ragBudgets = mergeMaxDocumentsUsedForContext(ragBudgets, deploymentRagMaxDocumentsUsedForContext);
        }
        Integer deploymentRagMaxContextChars = readPositiveIntegerOverride(
            orchestrationContext,
            OrchestrationContextMetadataKeys.RAG_MAX_CONTEXT_CHARS
        );
        if (deploymentRagMaxContextChars != null) {
            ragBudgets = mergeMaxContextChars(ragBudgets, deploymentRagMaxContextChars);
        }
        Boolean deploymentSmartSuggestionsEnabled = readSmartSuggestionsEnabledOverride(orchestrationContext);
        if (deploymentSmartSuggestionsEnabled != null) {
            suggestionsEnabled = deploymentSmartSuggestionsEnabled;
            suggestionsEnabledSource = "DEPLOYMENT_CONFIG";
        }
        OrchestrationPolicy.ResponseGenerationBudgets responseGenerationBudgets = mergeResponseGenerationBudgets(
            null,
            readPositiveIntegerOverride(
                orchestrationContext,
                OrchestrationContextMetadataKeys.RESPONSE_GENERATION_MAX_TOKENS_CONCISE
            ),
            readPositiveIntegerOverride(
                orchestrationContext,
                OrchestrationContextMetadataKeys.RESPONSE_GENERATION_MAX_TOKENS_STANDARD
            ),
            readPositiveIntegerOverride(
                orchestrationContext,
                OrchestrationContextMetadataKeys.RESPONSE_GENERATION_MAX_TOKENS_DEEP
            )
        );

        OrchestrationPolicy policy = new OrchestrationPolicy(
            profile,
            effectiveMode,
            normalizedPosition,
            informationModeEffective,
            new OrchestrationPolicy.OrchestrationCapabilities(
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
                forceGroundingEligibleReadActionPostGeneration
            ),
            readActionResolutionPolicy,
            ragBudgets,
            responseGenerationBudgets
        );

        OrchestrationContext updatedOrchestrationContext = orchestrationContext;
        if (orchestrationContext != null) {
            if (advancedRagOverride != null) {
                Map<String, Object> meta = new LinkedHashMap<>(orchestrationContext.getMetadata() != null
                    ? orchestrationContext.getMetadata()
                    : Map.of());
                meta.put(OrchestrationContextMetadataKeys.USE_ADVANCED_RAG, advancedRagOverride);
                updatedOrchestrationContext = orchestrationContext.toBuilder()
                    .metadata(Collections.unmodifiableMap(meta))
                    .orchestrationPolicy(policy)
                    .build();
            } else {
                updatedOrchestrationContext = orchestrationContext.toBuilder()
                    .orchestrationPolicy(policy)
                    .build();
            }
        }

        Map<String, Object> debug = new LinkedHashMap<>();
        debug.put("profile", policy.profile().name());
        debug.put("mode", policy.mode());
        debug.put("position", policy.position());
        debug.put("informationModeEffective", policy.informationMode().name());
        debug.put("modeSource", modeSource);
        debug.put("actionsEnabled", policy.capabilities().actionsEnabled());
        debug.put("retrievalEnabled", policy.capabilities().retrievalEnabled());
        debug.put("deepRetrievalEnabled", policy.capabilities().deepRetrievalEnabled());
        debug.put("forceRetrievalWhenTargetsPresent", policy.capabilities().forceRetrievalWhenTargetsPresent());
        debug.put("forceRetrievalConsiderStoredTargets", policy.capabilities().forceRetrievalConsiderStoredTargets());
        debug.put("forceGroundingEligibleReadActionPostGeneration", policy.capabilities().forceGroundingEligibleReadActionPostGeneration());
        debug.put("minimizeRagWhenPinnedTargetsCoverRequest", policy.capabilities().minimizeRagWhenPinnedTargetsCoverRequest());
        debug.put("suggestionsEnabled", policy.capabilities().suggestionsEnabled());
        debug.put("suggestionsEnabledSource", suggestionsEnabledSource);
        debug.put("exposeReadProbeFallbackAttempt", policy.capabilities().exposeReadProbeFallbackAttempt());
        debug.put("actionsPreferred", policy.capabilities().actionsPreferred());
        debug.put("knowledgeBaseOverviewEnabled", policy.capabilities().knowledgeBaseOverviewEnabled());
        debug.put("retrievalAllowlistRequired", policy.capabilities().retrievalAllowlistRequired());
        debug.put("vectorSpaceSelectionRequired", policy.capabilities().vectorSpaceSelectionRequired());
        if (policy.readActionResolutionPolicy() != null) {
            OrchestrationPolicy.ReadActionResolutionPolicy readPolicy = policy.readActionResolutionPolicy();
            debug.put("readActionResolutionEnabled", readPolicy.enabled());
            debug.put("readActionResolutionPlanningMode", readPolicy.planningMode().name());
            debug.put("readActionResolutionRequireAllowlist", readPolicy.requireAllowlist());
            debug.put("readActionResolutionMaxIterations", readPolicy.maxIterations());
            debug.put("readActionResolutionMaxActionsPerIteration", readPolicy.maxActionsPerIteration());
            debug.put("readActionResolutionMaxTotalActions", readPolicy.maxTotalActions());
            debug.put("readActionResolutionMaxParallelActions", readPolicy.maxParallelActions());
            debug.put("readActionResolutionMaxPlannerContextChars", readPolicy.maxPlannerContextChars());
            debug.put("readActionResolutionMaxActionEvidenceCharsPerAction", readPolicy.maxActionEvidenceCharsPerAction());
            debug.put("readActionResolutionRagCooperationMode", readPolicy.ragCooperationMode().name());
            debug.put("readActionResolutionRequireGroundingEligible", readPolicy.requireGroundingEligible());
            if (readPolicy.hasAllowedReadActions()) {
                debug.put("readActionResolutionAllowedReadActions", readPolicy.allowedReadActions());
            }
        }
        if (advancedRagOverride != null) {
            debug.put("advancedRagOverride", advancedRagOverride);
            debug.put("advancedRagOverrideSource", "MODE");
        }
        if (policy.ragBudgets() != null) {
            OrchestrationPolicy.RagBudgets b = policy.ragBudgets();
            if (b.fanoutEnabled() != null) {
                debug.put("ragFanoutEnabled", b.fanoutEnabled());
            }
            if (b.maxSpaces() != null) {
                debug.put("ragMaxSpaces", b.maxSpaces());
            }
            if (b.topKPerSpace() != null) {
                debug.put("ragTopKPerSpace", b.topKPerSpace());
            }
            if (b.maxDocumentsReturnedToClient() != null) {
                debug.put("ragMaxDocumentsReturnedToClient", b.maxDocumentsReturnedToClient());
            }
            if (b.maxDocumentsUsedForContext() != null) {
                debug.put("ragMaxDocumentsUsedForContext", b.maxDocumentsUsedForContext());
                debug.put(
                    "ragMaxDocumentsUsedForContextSource",
                    deploymentRagMaxDocumentsUsedForContext != null ? "DEPLOYMENT_CONFIG" : "MODE"
                );
            }
            if (b.maxContextChars() != null) {
                debug.put("ragMaxContextChars", b.maxContextChars());
                debug.put(
                    "ragMaxContextCharsSource",
                    deploymentRagMaxContextChars != null ? "DEPLOYMENT_CONFIG" : "MODE"
                );
            }
            if (b.similarityThreshold() != null) {
                debug.put("ragSimilarityThreshold", b.similarityThreshold());
                debug.put("ragSimilarityThresholdSource", deploymentRagSimilarityThreshold != null ? "DEPLOYMENT_CONFIG" : "MODE");
            }
            if (b.hasVectorSpaceAllowlist()) {
                debug.put("ragRetrievalVectorSpacesAllowlist", b.retrievalVectorSpacesAllowlist());
            }
        }
        if (policy.responseGenerationBudgets() != null) {
            OrchestrationPolicy.ResponseGenerationBudgets budgets = policy.responseGenerationBudgets();
            if (budgets.conciseMaxTokens() != null) {
                debug.put("responseGenerationMaxTokensConcise", budgets.conciseMaxTokens());
                debug.put("responseGenerationMaxTokensConciseSource", "DEPLOYMENT_CONFIG");
            }
            if (budgets.standardMaxTokens() != null) {
                debug.put("responseGenerationMaxTokensStandard", budgets.standardMaxTokens());
                debug.put("responseGenerationMaxTokensStandardSource", "DEPLOYMENT_CONFIG");
            }
            if (budgets.deepMaxTokens() != null) {
                debug.put("responseGenerationMaxTokensDeep", budgets.deepMaxTokens());
                debug.put("responseGenerationMaxTokensDeepSource", "DEPLOYMENT_CONFIG");
            }
        }

        PipelineContext updated = context.toBuilder()
            .orchestrationContext(updatedOrchestrationContext)
            .orchestrationPolicy(policy)
            .build()
            .withMetadata(METADATA_KEY_POLICY, Collections.unmodifiableMap(debug));

        log.debug("Resolved orchestration policy profile={}, mode={}, position={}, informationMode={} for request {}",
            policy.profile(),
            policy.mode(),
            policy.position(),
            policy.informationMode(),
            context.getRequestId());

        return updated;
    }

    private static String normalizeKey(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.toLowerCase();
    }

    private static <T> T lookupCaseInsensitive(Map<String, T> map, String normalizedKey) {
        if (map == null || map.isEmpty() || normalizedKey == null) {
            return null;
        }
        for (Map.Entry<String, T> entry : map.entrySet()) {
            String key = normalizeKey(entry.getKey());
            if (normalizedKey.equals(key)) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Double readSimilarityThresholdOverride(OrchestrationContext orchestrationContext) {
        if (orchestrationContext == null || orchestrationContext.getMetadata() == null) {
            return null;
        }
        Object raw = orchestrationContext.getMetadata().get(OrchestrationContextMetadataKeys.RAG_SIMILARITY_THRESHOLD);
        if (raw == null) {
            return null;
        }
        Double parsed = null;
        if (raw instanceof Number number) {
            parsed = number.doubleValue();
        } else if (raw instanceof String text) {
            try {
                parsed = Double.parseDouble(text.trim());
            } catch (NumberFormatException ignored) {
                parsed = null;
            }
        }
        if (parsed == null || !Double.isFinite(parsed) || parsed < 0.0d || parsed > 1.0d) {
            return null;
        }
        return parsed;
    }

    private Integer readPositiveIntegerOverride(OrchestrationContext orchestrationContext, String key) {
        if (orchestrationContext == null || orchestrationContext.getMetadata() == null) {
            return null;
        }
        Object raw = orchestrationContext.getMetadata().get(key);
        if (raw == null) {
            return null;
        }
        Integer parsed = null;
        if (raw instanceof Number number) {
            parsed = number.intValue();
        } else if (raw instanceof String text) {
            try {
                parsed = Integer.parseInt(text.trim());
            } catch (NumberFormatException ignored) {
                parsed = null;
            }
        }
        if (parsed == null || parsed <= 0) {
            return null;
        }
        return parsed;
    }

    private Boolean readSmartSuggestionsEnabledOverride(OrchestrationContext orchestrationContext) {
        if (orchestrationContext == null || orchestrationContext.getMetadata() == null) {
            return null;
        }
        Object raw = orchestrationContext.getMetadata().get(OrchestrationContextMetadataKeys.SMART_SUGGESTIONS_ENABLED);
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof String text) {
            String trimmed = text.trim();
            if ("true".equalsIgnoreCase(trimmed)) {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(trimmed)) {
                return Boolean.FALSE;
            }
        }
        return null;
    }

    private OrchestrationPolicy.RagBudgets mergeSimilarityThreshold(OrchestrationPolicy.RagBudgets ragBudgets,
                                                                    Double similarityThreshold) {
        return mergeRagBudgets(ragBudgets, null, null, similarityThreshold);
    }

    private OrchestrationPolicy.RagBudgets mergeMaxDocumentsUsedForContext(OrchestrationPolicy.RagBudgets ragBudgets,
                                                                           Integer maxDocumentsUsedForContext) {
        return mergeRagBudgets(ragBudgets, maxDocumentsUsedForContext, null, null);
    }

    private OrchestrationPolicy.RagBudgets mergeMaxContextChars(OrchestrationPolicy.RagBudgets ragBudgets,
                                                                Integer maxContextChars) {
        return mergeRagBudgets(ragBudgets, null, maxContextChars, null);
    }

    private OrchestrationPolicy.RagBudgets mergeRagBudgets(OrchestrationPolicy.RagBudgets ragBudgets,
                                                           Integer maxDocumentsUsedForContext,
                                                           Integer maxContextChars,
                                                           Double similarityThreshold) {
        if (ragBudgets == null) {
            return new OrchestrationPolicy.RagBudgets(
                null,
                null,
                null,
                null,
                maxDocumentsUsedForContext,
                maxContextChars,
                java.util.List.of(),
                similarityThreshold
            );
        }
        return new OrchestrationPolicy.RagBudgets(
            ragBudgets.fanoutEnabled(),
            ragBudgets.maxSpaces(),
            ragBudgets.topKPerSpace(),
            ragBudgets.maxDocumentsReturnedToClient(),
            maxDocumentsUsedForContext != null ? maxDocumentsUsedForContext : ragBudgets.maxDocumentsUsedForContext(),
            maxContextChars != null ? maxContextChars : ragBudgets.maxContextChars(),
            ragBudgets.retrievalVectorSpacesAllowlist(),
            similarityThreshold != null ? similarityThreshold : ragBudgets.similarityThreshold()
        );
    }

    private OrchestrationPolicy.ResponseGenerationBudgets mergeResponseGenerationBudgets(
        OrchestrationPolicy.ResponseGenerationBudgets budgets,
        Integer conciseMaxTokens,
        Integer standardMaxTokens,
        Integer deepMaxTokens
    ) {
        if (budgets == null) {
            return new OrchestrationPolicy.ResponseGenerationBudgets(
                conciseMaxTokens,
                standardMaxTokens,
                deepMaxTokens
            );
        }
        return new OrchestrationPolicy.ResponseGenerationBudgets(
            conciseMaxTokens != null ? conciseMaxTokens : budgets.conciseMaxTokens(),
            standardMaxTokens != null ? standardMaxTokens : budgets.standardMaxTokens(),
            deepMaxTokens != null ? deepMaxTokens : budgets.deepMaxTokens()
        );
    }
}
