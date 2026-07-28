package ai.fabric.intent;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.action.AIActionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Applies deterministic normalization and guardrails to extracted intents.
 *
 * <p>This component must not perform additional LLM calls.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentExtractionPostProcessor {

    private static final String METADATA_KEY_NORMALIZATION = "normalization";
    private static final String NORMALIZATION_KEY_RULES = "appliedRules";
    private static final String NORMALIZATION_KEY_RULE_COUNT = "ruleCount";

    private final AIActionRegistry actionHandlerRegistry;

    public MultiIntentResponse postProcess(MultiIntentResponse response, String originalQuery) {
        if (response == null) {
            throw new AIServiceException("Intent extraction response is null");
        }

        List<String> appliedRules = new ArrayList<>();

        response.normalize();
        coerceMisclassifiedActionIntents(response, appliedRules);
        validateResponse(response, originalQuery, appliedRules);
        attachNormalizationDiagnostics(response, appliedRules);

        if (!response.hasIntents()) {
            log.warn("Intent extractor returned no intents");
        }

        return response;
    }

    /**
     * Provider-agnostic guardrail: some models mislabel "summarize / explain" requests as ACTION intents
     * with an unregistered action name (e.g., "summarize"). When the intent also clearly requests RAG
     * (requiresRetrieval + requiresGeneration + vectorSpace), treat it as INFORMATION instead so the
     * pipeline can satisfy it via retrieval/generation rather than failing with ACTION_NOT_FOUND.
     */
    private void coerceMisclassifiedActionIntents(MultiIntentResponse response, List<String> appliedRules) {
        if (response == null || response.getIntents() == null || response.getIntents().isEmpty()) {
            return;
        }

        for (Intent intent : response.getIntents()) {
            if (intent == null || intent.getType() != IntentType.ACTION) {
                continue;
            }

            String actionName = intent.getIntentOrAction();
            if (!StringUtils.hasText(actionName)) {
                continue;
            }

            if (actionHandlerRegistry != null && actionHandlerRegistry.findHandler(actionName).isPresent()) {
                continue;
            }

            boolean looksLikeRagRequest =
                Boolean.TRUE.equals(intent.getRequiresRetrieval())
                    && Boolean.TRUE.equals(intent.getRequiresGeneration())
                    && StringUtils.hasText(intent.getVectorSpace());

            if (looksLikeRagRequest) {
                log.warn(
                    "Intent extractor misclassified a RAG request as ACTION (action='{}', vectorSpace='{}'). Treating as INFORMATION.",
                    actionName,
                    intent.getVectorSpace()
                );
                intent.setType(IntentType.INFORMATION);
                intent.setAction(null);
                recordRule(appliedRules, "COERCE_MISCLASSIFIED_ACTION_TO_INFORMATION");
            }
        }
    }

    private void validateResponse(MultiIntentResponse response, String originalQuery, List<String> appliedRules) {
        if (response.getIntents() == null) {
            response.setIntents(List.of());
        }

        for (Intent intent : response.getIntents()) {
            if (intent == null) {
                throw new AIServiceException("Intent is null");
            }
            if (!intent.hasValidType()) {
                throw new AIServiceException("Intent is missing a valid type attribute");
            }
            if (!intent.hasMeaningfulName()) {
                // Provider-agnostic tolerance: some models emit OUT_OF_SCOPE without a stable name.
                // For OUT_OF_SCOPE we do not require `intent` / `action` because execution does not depend on it.
                if (intent.getType() == IntentType.OUT_OF_SCOPE) {
                    intent.setIntent("out_of_scope");
                    intent.setAction(null);
                } else {
                    throw new AIServiceException("Intent is missing the 'intent' or 'action' field");
                }
            }
            canonicalizeActionName(intent, appliedRules);
            validateRelationshipActionParams(intent, originalQuery, appliedRules);
            if (intent.getRequiresRetrieval() == null) {
                intent.setRequiresRetrieval(intent.getType() == IntentType.INFORMATION);
            }
            if (Boolean.TRUE.equals(intent.getRequiresRetrieval()) && !StringUtils.hasText(intent.getVectorSpace())) {
                log.debug("Intent requires retrieval but vectorSpace is missing; deferring routing to VectorSpaceResolutionStep");
            }
        }

        if (response.getOrchestrationStrategy() == null) {
            response.setOrchestrationStrategy(deriveOrchestrationStrategy(response));
        }
    }

    private String deriveOrchestrationStrategy(MultiIntentResponse response) {
        boolean hasAction = response.getIntents().stream().anyMatch(Intent::isActionable);
        boolean requiresRetrieval = response.getIntents().stream()
            .anyMatch(intent -> Boolean.TRUE.equals(intent.getRequiresRetrieval()));

        if (hasAction && !requiresRetrieval) {
            return "DIRECT_ACTION";
        }
        if (requiresRetrieval) {
            return "RETRIEVE_AND_GENERATE";
        }
        return "ADMIT_UNKNOWN";
    }

    private void validateRelationshipActionParams(Intent intent, String originalQuery, List<String> appliedRules) {
        if (intent.getType() != IntentType.ACTION) {
            return;
        }

        String actionName = StringUtils.hasText(intent.getAction()) ? intent.getAction() : intent.getIntent();
        String canonicalActionName = actionName;
        if (actionHandlerRegistry != null && StringUtils.hasText(actionName)) {
            var metadataOpt = actionHandlerRegistry.findMetadata(actionName);
            if (metadataOpt != null) {
                canonicalActionName = metadataOpt
                    .map(ai.fabric.intent.action.AIActionMetaData::getName)
                    .orElse(actionName);
            }
        }
        if (!"relationship_query".equalsIgnoreCase(canonicalActionName)) {
            return;
        }

        coerceRelationshipQueryPostActionSummaryRequest(intent, appliedRules);

        Map<String, Object> params = intent.getActionParams();
        Map<String, Object> mutable = params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();

        Object rawQuery = mutable.get("query");
        if (rawQuery instanceof String text && StringUtils.hasText(text)) {
            String stripped = RelationshipQueryHintPrefix.stripIfPresent(text);
            if (!Objects.equals(text, stripped)) {
                recordRule(appliedRules, "RELATIONSHIP_QUERY_STRIP_HINT_PREFIX");
            }
            mutable.put("query", stripped);
        } else if (StringUtils.hasText(originalQuery)) {
            String stripped = RelationshipQueryHintPrefix.stripIfPresent(originalQuery);
            if (StringUtils.hasText(stripped)) {
                recordRule(appliedRules, "RELATIONSHIP_QUERY_DEFAULT_QUERY_PARAM");
                mutable.put("query", stripped);
            }
        }

        Object rawEntityTypes = mutable.get("entityTypes");

        List<String> normalizedEntityTypes;
        if (rawEntityTypes == null) {
            log.warn("Relationship query intent missing entityTypes - defaulting to empty list for actionParams");
            normalizedEntityTypes = List.of();
        } else if (rawEntityTypes instanceof List<?> list) {
            normalizedEntityTypes = list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .map(String::toLowerCase)
                .toList();
        } else if (rawEntityTypes instanceof String value) {
            String trimmed = value.trim();
            normalizedEntityTypes = trimmed.isEmpty() ? List.of() : List.of(trimmed.toLowerCase());
        } else {
            log.warn("Relationship query entityTypes should be List<String> or String but was {}", rawEntityTypes.getClass().getSimpleName());
            normalizedEntityTypes = List.of();
        }

        mutable.put("entityTypes", normalizedEntityTypes);
        if (!Objects.equals(rawEntityTypes, normalizedEntityTypes)) {
            recordRule(appliedRules, "NORMALIZE_ENTITY_TYPES");
        }
        intent.setActionParams(mutable);
    }

    private void coerceRelationshipQueryPostActionSummaryRequest(Intent intent, List<String> appliedRules) {
        if (intent == null) {
            return;
        }

        if (StringUtils.hasText(intent.getGenerationInstructions())) {
            intent.setRequiresGeneration(true);
            return;
        }
        if (Boolean.TRUE.equals(intent.getRequiresGeneration())) {
            return;
        }

        NextStepRecommendation next = intent.getNextStepRecommended();
        if (next == null) {
            return;
        }

        // If the model recommends a follow-up that targets a vector space, keep it as a smart suggestion.
        // Post-action generation should be grounded in the already retrieved SQL results, not trigger retrieval.
        if (StringUtils.hasText(next.getVectorSpace())) {
            return;
        }
        if (!StringUtils.hasText(next.getQuery())) {
            return;
        }

        // Provider-agnostic: some models represent "then summarize/explain the results" as nextStepRecommended
        // instead of the dedicated post-action generation fields. Convert it into a post-action generation request
        // for relationship_query so the pipeline can run a grounded summary after executing the action.
        intent.setRequiresGeneration(true);
        intent.setGenerationInstructions(next.getQuery().trim());
        recordRule(appliedRules, "RELATIONSHIP_QUERY_COERCE_POST_ACTION_INSTRUCTIONS");
    }

    private void canonicalizeActionName(Intent intent, List<String> appliedRules) {
        if (intent == null || intent.getType() != IntentType.ACTION) {
            return;
        }
        if (actionHandlerRegistry == null) {
            return;
        }

        String actionName = StringUtils.hasText(intent.getAction()) ? intent.getAction() : intent.getIntent();
        if (!StringUtils.hasText(actionName)) {
            return;
        }

        var metadataOpt = actionHandlerRegistry.findMetadata(actionName);
        if (metadataOpt == null || metadataOpt.isEmpty()) {
            return;
        }

        String canonical = metadataOpt.get().getName();
        if (!StringUtils.hasText(canonical)) {
            return;
        }
        if (!canonical.equals(intent.getAction())) {
            intent.setAction(canonical);
            recordRule(appliedRules, "CANONICALIZE_ACTION_NAME");
        }
    }

    private void attachNormalizationDiagnostics(MultiIntentResponse response, List<String> appliedRules) {
        if (response == null || appliedRules == null || appliedRules.isEmpty()) {
            return;
        }

        Map<String, Object> normalization = Map.of(
            NORMALIZATION_KEY_RULES, List.copyOf(appliedRules),
            NORMALIZATION_KEY_RULE_COUNT, appliedRules.size()
        );

        Map<String, Object> merged = new LinkedHashMap<>();
        if (response.getMetadata() != null && !response.getMetadata().isEmpty()) {
            merged.putAll(response.getMetadata());
        }
        merged.put(METADATA_KEY_NORMALIZATION, normalization);
        response.setMetadata(Map.copyOf(merged));
    }

    private void recordRule(List<String> appliedRules, String ruleId) {
        if (appliedRules == null || !StringUtils.hasText(ruleId)) {
            return;
        }
        if (!appliedRules.contains(ruleId)) {
            appliedRules.add(ruleId);
        }
    }
}
