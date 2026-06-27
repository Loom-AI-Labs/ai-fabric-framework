package ai.fabric.intent.orchestration.information;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionPayload;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded planner for live informational resolution using allowlisted read-only actions.
 *
 * <p>This is intentionally fail-closed: only READ actions that are explicitly marked as planner-eligible
 * and allowed by the resolved orchestration policy may be executed.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReadActionResolutionService {
    private static final String TEMPLATE_FAMILY = "orchestration/read-action-resolution";
    private static final String TEMPLATE_SYSTEM = "system";
    private static final String TEMPLATE_USER = "user";

    private static final String PLACEHOLDER_MODE = "mode";
    private static final String PLACEHOLDER_QUERY = "query";
    private static final String PLACEHOLDER_INTENT_JSON = "intent_json";
    private static final String PLACEHOLDER_ELIGIBLE_ACTIONS_JSON = "eligible_actions_json";
    private static final String PLACEHOLDER_PRIOR_EVIDENCE_JSON = "prior_evidence_json";
    private static final String PLACEHOLDER_MAX_ACTIONS_PER_ITERATION = "max_actions_per_iteration";
    private static final String PLACEHOLDER_MAX_TOTAL_ACTIONS = "max_total_actions";
    private static final String PLACEHOLDER_RAG_COOPERATION_MODE = "rag_cooperation_mode";
    private static final String PLACEHOLDER_ITERATION = "iteration";
    private static final String PLACEHOLDER_MAX_ITERATIONS = "max_iterations";
    private static final Set<String> NON_GROUNDING_EVIDENCE_KEYS = Set.of(
        "action",
        "category",
        "success",
        "message",
        "errorcode",
        "query",
        "lookup",
        "lookupmethod",
        "truncated",
        "truncateditems",
        "omitteditems",
        "_truncated",
        "_truncateditems",
        "_omitteditems",
        "summary"
    );
    private static final Set<String> COUNT_EVIDENCE_KEYS = Set.of(
        "count",
        "total",
        "totalcount",
        "resultcount",
        "itemcount",
        "documentcount",
        "policycount"
    );
    private static final int MAX_SANITIZED_EVIDENCE_MAP_ENTRIES = 16;

    private final AICoreService aiCoreService;
    private final AIActionRegistry actionRegistry;
    private final IntentExtractionJsonSupport jsonSupport;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    public ResolutionOutcome resolve(Intent intent,
                                     OrchestrationContext orchestrationContext,
                                     PipelineContext pipelineContext) {
        OrchestrationPolicy policy = pipelineContext != null ? pipelineContext.getOrchestrationPolicy() : null;
        OrchestrationPolicy.ReadActionResolutionPolicy readPolicy = policy != null
            ? policy.readActionResolutionPolicy()
            : OrchestrationPolicy.ReadActionResolutionPolicy.defaults();

        if (!readPolicy.enabled()) {
            return ResolutionOutcome.skipped("DISABLED_BY_POLICY");
        }
        if (intent == null) {
            return ResolutionOutcome.skipped("NO_INTENT");
        }
        if (intent.getType() != ai.fabric.dto.IntentType.INFORMATION) {
            return ResolutionOutcome.skipped("NON_INFORMATION_INTENT");
        }

        List<EligibleReadAction> eligibleActions = resolveEligibleReadActions(orchestrationContext, readPolicy);
        if (eligibleActions.isEmpty()) {
            return ResolutionOutcome.skipped("NO_ELIGIBLE_READ_ACTIONS");
        }

        String query = resolvePlannerQuery(intent, pipelineContext);
        if (!StringUtils.hasText(query)) {
            return ResolutionOutcome.skipped("NO_QUERY");
        }

        int maxIterations = readPolicy.planningMode() == ai.fabric.config.OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE
            ? readPolicy.maxIterations()
            : 1;

        List<ExecutedReadAction> executedActions = new ArrayList<>();
        List<Map<String, Object>> plannerIterations = new ArrayList<>();
        Set<String> executionKeys = new LinkedHashSet<>();
        PlannerDecision lastDecision = null;

        for (int iteration = 1; iteration <= maxIterations; iteration++) {
            PlannerDecision decision = planActions(
                query,
                intent,
                orchestrationContext,
                readPolicy,
                eligibleActions,
                executedActions,
                iteration,
                maxIterations
            );
            if (decision == null) {
                plannerIterations.add(Map.of(
                    "iteration", iteration,
                    "status", "FAILED",
                    "reason", "PLANNER_RETURNED_NO_DECISION"
                ));
                break;
            }
            lastDecision = decision;
            plannerIterations.add(decision.diagnostics());

            List<PlannerActionProposal> approved = approveActionProposals(
                decision,
                eligibleActions,
                executedActions.size(),
                readPolicy,
                executionKeys
            );
            if (approved.isEmpty()) {
                if (readPolicy.planningMode() != ai.fabric.config.OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE
                    || !Boolean.TRUE.equals(decision.needsMoreSteps)) {
                    break;
                }
                continue;
            }

            for (PlannerActionProposal proposal : approved) {
                executedActions.add(executeAction(proposal, orchestrationContext, pipelineContext, readPolicy));
            }

            if (readPolicy.planningMode() != ai.fabric.config.OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE
                || !Boolean.TRUE.equals(decision.needsMoreSteps)) {
                break;
            }
        }

        boolean hasSuccessfulActionEvidence = executedActions.stream().anyMatch(ExecutedReadAction::groundingUsable);
        long insufficientActionEvidenceCount = executedActions.stream()
            .filter(action -> action != null && !action.groundingUsable())
            .count();
        boolean useRag = shouldUseRag(
            lastDecision,
            hasSuccessfulActionEvidence,
            insufficientActionEvidenceCount > 0,
            readPolicy
        );
        String evidenceContext = buildEvidenceContext(executedActions, readPolicy.maxPlannerContextChars());
        List<String> preferredVectorSpaces = lastDecision != null
            ? normalizeVectorSpaces(lastDecision.suggestedVectorSpaces)
            : List.of();

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("enabled", true);
        diagnostics.put("planningMode", readPolicy.planningMode().name());
        diagnostics.put("ragCooperationMode", readPolicy.ragCooperationMode().name());
        diagnostics.put("attempted", true);
        diagnostics.put("eligibleReadActionsCount", eligibleActions.size());
        diagnostics.put("eligibleReadActionNames", eligibleActions.stream().map(EligibleReadAction::name).toList());
        diagnostics.put("iterations", Collections.unmodifiableList(plannerIterations));
        diagnostics.put("executedActions", executedActions.stream().map(ExecutedReadAction::toDiagnosticMap).toList());
        diagnostics.put("executedActionsCount", executedActions.size());
        diagnostics.put("groundingUsableActionCount", executedActions.stream().filter(ExecutedReadAction::groundingUsable).count());
        diagnostics.put("insufficientActionEvidenceCount", insufficientActionEvidenceCount);
        diagnostics.put("useRag", useRag);
        if (lastDecision != null) {
            diagnostics.put("finalDecision", lastDecision.decision != null ? lastDecision.decision.name() : null);
            if (StringUtils.hasText(lastDecision.missingEvidenceReason)) {
                diagnostics.put("missingEvidenceReason", lastDecision.missingEvidenceReason);
            }
        }
        if (!preferredVectorSpaces.isEmpty()) {
            diagnostics.put("preferredVectorSpaces", preferredVectorSpaces);
        }

        return new ResolutionOutcome(
            true,
            null,
            lastDecision != null ? lastDecision.decision : PlannerDecisionType.USE_RAG_ONLY,
            useRag,
            hasSuccessfulActionEvidence,
            evidenceContext,
            preferredVectorSpaces,
            Collections.unmodifiableList(executedActions),
            Collections.unmodifiableMap(diagnostics)
        );
    }

    private List<EligibleReadAction> resolveEligibleReadActions(OrchestrationContext context,
                                                                OrchestrationPolicy.ReadActionResolutionPolicy readPolicy) {
        List<AIActionMetaData> actions = actionRegistry.getAllMetadata();
        if (actions == null || actions.isEmpty()) {
            return List.of();
        }

        Map<String, EligibleReadAction> eligible = new LinkedHashMap<>();
        for (AIActionMetaData metadata : actions) {
            if (metadata == null || !StringUtils.hasText(metadata.getName())) {
                continue;
            }
            if (metadata.getAccessMode() != ActionAccessMode.READ) {
                continue;
            }
            if (!metadata.isReadActionResolutionEligible()) {
                continue;
            }
            if (readPolicy.requireGroundingEligible() && !metadata.isGroundingEligible()) {
                continue;
            }
            String normalizedName = metadata.getName().trim().toLowerCase(Locale.ROOT);
            if (readPolicy.requireAllowlist() && !readPolicy.allowedReadActions().contains(normalizedName)) {
                continue;
            }
            if (!readPolicy.requireAllowlist()
                && readPolicy.hasAllowedReadActions()
                && !readPolicy.allowedReadActions().contains(normalizedName)) {
                continue;
            }
            Optional<AIActionHandler> handler = actionRegistry.findHandler(metadata.getName());
            if (handler.isEmpty()) {
                continue;
            }
            if (context != null && context.isAnonymous() && !metadata.isAnonymousAllowed()) {
                continue;
            }
            eligible.put(
                normalizedName,
                new EligibleReadAction(
                    metadata.getName(),
                    metadata,
                    handler.get()
                )
            );
        }
        return eligible.isEmpty() ? List.of() : List.copyOf(eligible.values());
    }

    private PlannerDecision planActions(String query,
                                        Intent intent,
                                        OrchestrationContext orchestrationContext,
                                        OrchestrationPolicy.ReadActionResolutionPolicy readPolicy,
                                        List<EligibleReadAction> eligibleActions,
                                        List<ExecutedReadAction> executedActions,
                                        int iteration,
                                        int maxIterations) {
        try {
            String systemPrompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM).template(),
                Map.of()
            );
            String userPrompt = promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_USER).template(),
                Map.of(
                    PLACEHOLDER_MODE, orchestrationContext != null && StringUtils.hasText(orchestrationContext.getMode())
                        ? orchestrationContext.getMode()
                        : "",
                    PLACEHOLDER_QUERY, query,
                    PLACEHOLDER_INTENT_JSON, writeJson(buildIntentPromptModel(intent)),
                    PLACEHOLDER_ELIGIBLE_ACTIONS_JSON, writeJson(buildEligibleActionsPromptModel(eligibleActions)),
                    PLACEHOLDER_PRIOR_EVIDENCE_JSON, writeJson(buildPriorEvidencePromptModel(executedActions)),
                    PLACEHOLDER_MAX_ACTIONS_PER_ITERATION, String.valueOf(readPolicy.maxActionsPerIteration()),
                    PLACEHOLDER_MAX_TOTAL_ACTIONS, String.valueOf(readPolicy.maxTotalActions()),
                    PLACEHOLDER_RAG_COOPERATION_MODE, readPolicy.ragCooperationMode().name(),
                    PLACEHOLDER_ITERATION, String.valueOf(iteration),
                    PLACEHOLDER_MAX_ITERATIONS, String.valueOf(maxIterations)
                )
            );

            AIGenerationResponse response = aiCoreService.generateContent(
                AIGenerationRequest.builder()
                    .entityId("read-action-resolution-" + UUID.randomUUID())
                    .entityType("read_action_resolution")
                    .generationType("read_action_resolution")
                    .systemPrompt(systemPrompt)
                    .prompt(userPrompt)
                    .parameters(jsonSupport.jsonOnlyResponseParameters())
                    .authContext(OrchestrationAuthContextResolver.from(orchestrationContext))
                    .build(),
                LlmPurpose.ORCHESTRATION
            );

            String content = response != null ? response.getContent() : null;
            if (!StringUtils.hasText(content)) {
                return null;
            }
            PlannerPayload payload = jsonSupport.parsePayload(content, PlannerPayload.class);
            PlannerDecisionType decisionType = PlannerDecisionType.from(payload.decision);
            if (decisionType == null) {
                return null;
            }
            List<PlannerActionProposal> proposals = new ArrayList<>();
            if (payload.actions != null) {
                for (PlannerActionPayload action : payload.actions) {
                    if (action == null || !StringUtils.hasText(action.name)) {
                        continue;
                    }
                    proposals.add(new PlannerActionProposal(
                        action.name.trim(),
                        action.params != null ? Map.copyOf(action.params) : Map.of(),
                        action.priority != null ? action.priority : proposals.size() + 1
                    ));
                }
            }
            proposals.sort(java.util.Comparator.comparingInt(PlannerActionProposal::priority));
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("iteration", iteration);
            diagnostics.put("status", "PLANNED");
            diagnostics.put("decision", decisionType.name());
            diagnostics.put("proposedActions", proposals.stream().map(PlannerActionProposal::toDiagnosticMap).toList());
            diagnostics.put("needsMoreSteps", payload.needsMoreSteps);
            if (payload.suggestedVectorSpaces != null && !payload.suggestedVectorSpaces.isEmpty()) {
                diagnostics.put("suggestedVectorSpaces", normalizeVectorSpaces(payload.suggestedVectorSpaces));
            }
            return new PlannerDecision(
                decisionType,
                List.copyOf(proposals),
                payload.needsMoreSteps,
                payload.missingEvidenceReason,
                payload.suggestedVectorSpaces != null ? List.copyOf(payload.suggestedVectorSpaces) : List.of(),
                Collections.unmodifiableMap(diagnostics)
            );
        } catch (Exception ex) {
            log.warn("Read-action planner failed: {}", ex.getMessage(), ex);
            return null;
        }
    }

    private List<PlannerActionProposal> approveActionProposals(PlannerDecision decision,
                                                               List<EligibleReadAction> eligibleActions,
                                                               int executedCount,
                                                               OrchestrationPolicy.ReadActionResolutionPolicy readPolicy,
                                                               Set<String> executionKeys) {
        if (decision == null || decision.actions == null || decision.actions.isEmpty()) {
            return List.of();
        }
        Map<String, EligibleReadAction> eligibleByName = new LinkedHashMap<>();
        for (EligibleReadAction action : eligibleActions) {
            eligibleByName.put(action.name().trim().toLowerCase(Locale.ROOT), action);
        }

        int remainingBudget = Math.max(0, readPolicy.maxTotalActions() - executedCount);
        if (remainingBudget == 0) {
            return List.of();
        }

        List<PlannerActionProposal> approved = new ArrayList<>();
        for (PlannerActionProposal proposal : decision.actions) {
            if (approved.size() >= readPolicy.maxActionsPerIteration() || approved.size() >= remainingBudget) {
                break;
            }
            if (proposal == null || !StringUtils.hasText(proposal.name())) {
                continue;
            }
            EligibleReadAction eligible = eligibleByName.get(proposal.name().trim().toLowerCase(Locale.ROOT));
            if (eligible == null) {
                continue;
            }
            if (!hasRequiredParams(eligible.metadata(), proposal.params())) {
                continue;
            }
            String executionKey = buildExecutionKey(eligible.name(), proposal.params());
            if (!executionKeys.add(executionKey)) {
                continue;
            }
            approved.add(new PlannerActionProposal(eligible.name(), proposal.params(), proposal.priority()));
        }
        return approved.isEmpty() ? List.of() : List.copyOf(approved);
    }

    private ExecutedReadAction executeAction(PlannerActionProposal proposal,
                                             OrchestrationContext orchestrationContext,
                                             PipelineContext pipelineContext,
                                             OrchestrationPolicy.ReadActionResolutionPolicy readPolicy) {
        Optional<AIActionHandler> handlerOpt = actionRegistry.findHandler(proposal.name());
        Optional<AIActionMetaData> metadataOpt = actionRegistry.findMetadata(proposal.name());
        if (handlerOpt.isEmpty() || metadataOpt.isEmpty()) {
            return ExecutedReadAction.failure(proposal.name(), proposal.params(), "ACTION_NOT_AVAILABLE", null, null);
        }

        AIActionHandler handler = handlerOpt.get();
        AIActionMetaData metadata = metadataOpt.get();
        ActionContext actionContext = new ActionContext(orchestrationContext, pipelineContext, proposal.params());

        if (orchestrationContext != null && orchestrationContext.isAnonymous() && !metadata.isAnonymousAllowed()) {
            return ExecutedReadAction.failure(proposal.name(), proposal.params(), "ANONYMOUS_NOT_ALLOWED", metadata, null);
        }
        if (!handler.validateActionAllowed(actionContext)) {
            return ExecutedReadAction.failure(proposal.name(), proposal.params(), "ACTION_NOT_ALLOWED", metadata, null);
        }

        try {
            ActionResult result = handler.executeAction(proposal.params(), actionContext);
            EvidenceSnippet evidence = buildEvidenceSnippet(
                handler,
                result,
                actionContext,
                readPolicy.maxActionEvidenceCharsPerAction()
            );
            return new ExecutedReadAction(
                proposal.name(),
                Collections.unmodifiableMap(new LinkedHashMap<>(proposal.params())),
                metadata,
                result,
                result != null && result.isSuccess() && evidence.groundingUsable(),
                evidence.summary(),
                null
            );
        } catch (Exception ex) {
            log.warn("Read-action resolution action '{}' failed: {}", proposal.name(), ex.getMessage(), ex);
            ActionResult errorResult = handler.handleError(ex, actionContext);
            EvidenceSnippet evidence = buildEvidenceSnippet(
                handler,
                errorResult,
                actionContext,
                readPolicy.maxActionEvidenceCharsPerAction()
            );
            return new ExecutedReadAction(
                proposal.name(),
                Collections.unmodifiableMap(new LinkedHashMap<>(proposal.params())),
                metadata,
                errorResult,
                false,
                evidence.summary(),
                ex.getMessage()
            );
        }
    }

    private boolean hasRequiredParams(AIActionMetaData metadata, Map<String, Object> params) {
        if (metadata == null || metadata.getRequiredParameters() == null || metadata.getRequiredParameters().isEmpty()) {
            return true;
        }
        for (String required : metadata.getRequiredParameters()) {
            if (!StringUtils.hasText(required)) {
                continue;
            }
            Object value = params != null ? params.get(required) : null;
            if (value == null) {
                return false;
            }
            if (value instanceof String text && !StringUtils.hasText(text)) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldUseRag(PlannerDecision decision,
                                 boolean hasSuccessfulActionEvidence,
                                 boolean hasInsufficientActionEvidence,
                                 OrchestrationPolicy.ReadActionResolutionPolicy readPolicy) {
        if (decision == null) {
            return readPolicy.ragCooperationMode() != ai.fabric.config.OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE;
        }
        return switch (decision.decision) {
            case ANSWER_FROM_CONTEXT -> (!hasSuccessfulActionEvidence || hasInsufficientActionEvidence)
                && readPolicy.ragCooperationMode() != ai.fabric.config.OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE;
            case EXECUTE_READ_ACTIONS -> (!hasSuccessfulActionEvidence || hasInsufficientActionEvidence)
                && readPolicy.ragCooperationMode() != ai.fabric.config.OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE;
            case EXECUTE_READ_ACTIONS_AND_RAG, USE_RAG_ONLY -> readPolicy.ragCooperationMode()
                != ai.fabric.config.OrchestrationProperties.ReadActionResolutionRagCooperationMode.NONE;
        };
    }

    private EvidenceSnippet buildEvidenceSnippet(AIActionHandler handler,
                                                 ActionResult result,
                                                 ActionContext actionContext,
                                                 int maxChars) {
        if (result == null) {
            return EvidenceSnippet.empty();
        }
        try {
            Optional<Map<String, Object>> llmFacts = handler.buildPostActionLlmFacts(result, actionContext);
            if (llmFacts.isPresent() && llmFacts.get() != null && !llmFacts.get().isEmpty()) {
                Object sanitized = sanitizeValue(llmFacts.get(), 0);
                return new EvidenceSnippet(
                    writeBoundedEvidenceJson(sanitized, maxChars),
                    hasSubstantiveEvidence(sanitized)
                );
            }
        } catch (Exception ex) {
            log.debug("Read-action resolution facts builder failed: {}", ex.getMessage());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", result.isSuccess());
        if (StringUtils.hasText(result.getMessage())) {
            payload.put("message", result.getMessage());
        }
        if (StringUtils.hasText(result.getErrorCode())) {
            payload.put("errorCode", result.getErrorCode());
        }
        if (result.getData() != null) {
            payload.put("data", sanitizePayload(result.getData()));
        }
        if (result.getPinnedTargets() != null && !result.getPinnedTargets().isEmpty()) {
            payload.put("pinnedTargets", result.getPinnedTargets());
        }
        Object sanitized = sanitizeValue(payload, 0);
        return new EvidenceSnippet(
            writeBoundedEvidenceJson(sanitized, maxChars),
            hasSubstantiveEvidence(sanitized)
        );
    }

    private boolean hasSubstantiveEvidence(Object value) {
        return hasSubstantiveEvidence(null, value, 0);
    }

    private boolean hasSubstantiveEvidence(String key, Object value, int depth) {
        if (value == null || depth > 6) {
            return false;
        }
        String normalizedKey = normalizeEvidenceKey(key);
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                String childKey = String.valueOf(entry.getKey());
                if (isNonGroundingEvidenceKey(childKey)) {
                    continue;
                }
                if (hasSubstantiveEvidence(childKey, entry.getValue(), depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (hasSubstantiveEvidence(key, item, depth + 1)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof String text) {
            return StringUtils.hasText(text) && !isNonGroundingEvidenceKey(normalizedKey);
        }
        if (value instanceof Number number) {
            if (COUNT_EVIDENCE_KEYS.contains(normalizedKey)) {
                return number.doubleValue() > 0;
            }
            return StringUtils.hasText(normalizedKey) && !isNonGroundingEvidenceKey(normalizedKey);
        }
        if (value instanceof Boolean) {
            return StringUtils.hasText(normalizedKey) && !isNonGroundingEvidenceKey(normalizedKey);
        }
        return StringUtils.hasText(String.valueOf(value)) && !isNonGroundingEvidenceKey(normalizedKey);
    }

    private boolean isNonGroundingEvidenceKey(String key) {
        String normalized = normalizeEvidenceKey(key);
        return !StringUtils.hasText(normalized) || NON_GROUNDING_EVIDENCE_KEYS.contains(normalized);
    }

    private String normalizeEvidenceKey(String key) {
        return StringUtils.hasText(key)
            ? key.trim().replace("-", "").replace("_", "").toLowerCase(Locale.ROOT)
            : "";
    }

    private String writeBoundedEvidenceJson(Object value, int maxChars) {
        String json = writeJson(value);
        if (maxChars <= 0 || json.length() <= maxChars) {
            return json;
        }

        for (int itemLimit : List.of(5, 3, 1)) {
            Object prioritized = prioritizeEvidence(value, itemLimit);
            json = writeJson(prioritized);
            if (json.length() <= maxChars) {
                return json;
            }
        }

        for (int itemLimit : List.of(3, 1)) {
            Object compacted = shrinkEvidenceLists(value, itemLimit);
            json = writeJson(compacted);
            if (json.length() <= maxChars) {
                return json;
            }
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("truncated", true);
        fallback.put("summary", truncate(String.valueOf(value), Math.min(400, Math.max(80, maxChars - 80))));
        json = writeJson(fallback);
        return json.length() <= maxChars ? json : "{\"truncated\":true}";
    }

    private Object shrinkEvidenceLists(Object value, int maxItems) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> compacted = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                compacted.put(String.valueOf(entry.getKey()), shrinkEvidenceLists(entry.getValue(), maxItems));
            }
            return compacted;
        }
        if (value instanceof List<?> list) {
            List<Object> compacted = new ArrayList<>();
            int limit = Math.min(list.size(), Math.max(0, maxItems));
            for (int i = 0; i < limit; i++) {
                compacted.add(shrinkEvidenceLists(list.get(i), maxItems));
            }
            if (list.size() > limit) {
                compacted.add(Map.of("_omittedItems", list.size() - limit));
            }
            return compacted;
        }
        return value;
    }

    private Object prioritizeEvidence(Object value, int maxItems) {
        if (!(value instanceof Map<?, ?> map)) {
            return shrinkEvidenceLists(value, maxItems);
        }
        Map<String, Object> prioritized = new LinkedHashMap<>();
        List<String> commonKeys = List.of(
            "action",
            "category",
            "success",
            "message",
            "errorCode",
            "query",
            "count",
            "totalResults",
            "returnedResults",
            "lookup",
            "lookupMethod"
        );
        for (String key : commonKeys) {
            putPrioritizedEvidence(map, prioritized, key, maxItems);
        }

        boolean hasConstraintEvidence = map.keySet().stream()
            .filter(key -> key != null)
            .map(String::valueOf)
            .anyMatch(key -> key.endsWith("ConstraintMatches"));
        List<String> suffixPriority = hasConstraintEvidence
            ? List.of(
                "ConstraintMatches",
                "ConstraintMatchCount",
                "Summary"
            )
            : List.of(
                "Summary",
                "documents",
                "records",
                "results",
                "items",
                "objects"
            );
        for (String suffix : suffixPriority) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                String key = String.valueOf(entry.getKey());
                if (prioritized.containsKey(key)) {
                    continue;
                }
                if (key.endsWith(suffix) || key.equals(suffix)) {
                    prioritized.put(key, shrinkEvidenceLists(entry.getValue(), maxItems));
                }
            }
        }
        return prioritized.isEmpty() ? shrinkEvidenceLists(value, maxItems) : prioritized;
    }

    private void putPrioritizedEvidence(Map<?, ?> source, Map<String, Object> target, String key, int maxItems) {
        if (source == null || target == null || !StringUtils.hasText(key)) {
            return;
        }
        Object value = mapValueIgnoreCase(source, key);
        if (value != null) {
            target.put(key, shrinkEvidenceLists(value, maxItems));
        }
    }

    private Object mapValueIgnoreCase(Map<?, ?> source, String key) {
        if (source == null || key == null) {
            return null;
        }
        Object direct = source.get(key);
        if (direct != null) {
            return direct;
        }
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (entry != null && entry.getKey() != null && key.equalsIgnoreCase(String.valueOf(entry.getKey()))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private Object sanitizePayload(ActionPayload payload) {
        if (payload == null) {
            return null;
        }
        return sanitizeValue(payload.toMap(), 0);
    }

    private Object sanitizeValue(Object value, int depth) {
        if (value == null) {
            return null;
        }
        if (depth > 3) {
            return truncate(String.valueOf(value), 240);
        }
        if (value instanceof String text) {
            return truncate(text, 240);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sanitized = new LinkedHashMap<>();
            int count = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry == null || entry.getKey() == null) {
                    continue;
                }
                if (++count > MAX_SANITIZED_EVIDENCE_MAP_ENTRIES) {
                    sanitized.put("_truncated", true);
                    break;
                }
                sanitized.put(String.valueOf(entry.getKey()), sanitizeValue(entry.getValue(), depth + 1));
            }
            return sanitized;
        }
        if (value instanceof List<?> list) {
            List<Object> sanitized = new ArrayList<>();
            int limit = Math.min(list.size(), 5);
            for (int i = 0; i < limit; i++) {
                sanitized.add(sanitizeValue(list.get(i), depth + 1));
            }
            if (list.size() > limit) {
                sanitized.add(Map.of("_truncatedItems", list.size() - limit));
            }
            return sanitized;
        }
        return truncate(String.valueOf(value), 240);
    }

    private String buildEvidenceContext(List<ExecutedReadAction> executedActions, int maxChars) {
        if (executedActions == null || executedActions.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder(Math.min(maxChars, 4096));
        out.append("READ ACTION EVIDENCE\n");
        for (ExecutedReadAction executedAction : executedActions) {
            if (executedAction == null || !StringUtils.hasText(executedAction.evidenceSummary)) {
                continue;
            }
            out.append("- action: ").append(executedAction.actionName).append('\n');
            out.append("  success: ").append(executedAction.actionResult != null && executedAction.actionResult.isSuccess()).append('\n');
            if (executedAction.actionResult != null && StringUtils.hasText(executedAction.actionResult.getMessage())) {
                out.append("  message: ").append(truncate(executedAction.actionResult.getMessage(), 200)).append('\n');
            }
            out.append("  evidence: ").append(executedAction.evidenceSummary).append('\n');
            if (out.length() >= maxChars) {
                break;
            }
        }
        return truncate(out.toString(), maxChars);
    }

    private String resolvePlannerQuery(Intent intent, PipelineContext pipelineContext) {
        if (pipelineContext != null && StringUtils.hasText(pipelineContext.getEffectiveQuery())) {
            return pipelineContext.getEffectiveQuery().trim();
        }
        if (intent != null && StringUtils.hasText(intent.getOptimizedQuery())) {
            return intent.getOptimizedQuery().trim();
        }
        return intent != null ? intent.getIntentOrAction() : null;
    }

    private Map<String, Object> buildIntentPromptModel(Intent intent) {
        if (intent == null) {
            return Map.of();
        }
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("type", intent.getType() != null ? intent.getType().name() : null);
        model.put("intent", intent.getIntent());
        model.put("vectorSpace", intent.getVectorSpace());
        model.put("requiresRetrieval", intent.getRequiresRetrieval());
        model.put("requiresGeneration", intent.getRequiresGeneration());
        model.put("optimizedQuery", intent.getOptimizedQuery());
        model.put("directAnswer", intent.getDirectAnswer());
        return model;
    }

    private List<Map<String, Object>> buildEligibleActionsPromptModel(List<EligibleReadAction> eligibleActions) {
        if (eligibleActions == null || eligibleActions.isEmpty()) {
            return List.of();
        }
        return eligibleActions.stream()
            .map(action -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("name", action.name());
                item.put("description", action.metadata().getDescription());
                item.put("category", action.metadata().getCategory());
                item.put("requiredParameters", publicRequiredParameters(action.metadata()));
                item.put("parameterSchemas", publicParameterSchemas(action.metadata()));
                item.put("groundingEligible", action.metadata().isGroundingEligible());
                return Collections.unmodifiableMap(item);
            })
            .toList();
    }

    private Set<String> publicRequiredParameters(AIActionMetaData metadata) {
        if (metadata == null || metadata.getRequiredParameters() == null || metadata.getRequiredParameters().isEmpty()) {
            return Set.of();
        }
        return metadata.getRequiredParameters().stream()
            .filter(StringUtils::hasText)
            .map(String::trim)
            .filter(parameter -> isUserVisibleParameter(metadata, parameter))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<String, AIActionParamSchema> publicParameterSchemas(AIActionMetaData metadata) {
        if (metadata == null || metadata.getParameterSchemas() == null || metadata.getParameterSchemas().isEmpty()) {
            return Map.of();
        }
        Map<String, AIActionParamSchema> out = new LinkedHashMap<>();
        metadata.getParameterSchemas().forEach((name, schema) -> {
            if (StringUtils.hasText(name) && isUserVisibleParameter(metadata, name)) {
                out.put(name.trim(), schema);
            }
        });
        return Collections.unmodifiableMap(out);
    }

    private boolean isUserVisibleParameter(AIActionMetaData metadata, String parameter) {
        if (!StringUtils.hasText(parameter)) {
            return false;
        }
        String normalized = parameter.trim();
        if ("shopperSessionId".equals(normalized) || "confirmationAccepted".equals(normalized)) {
            return false;
        }
        AIActionParamSchema schema = paramSchema(metadata, normalized);
        if (schema == null) {
            return true;
        }
        if (Boolean.FALSE.equals(schema.getAskUser())) {
            return false;
        }
        String visibility = schema.getVisibility();
        if (!StringUtils.hasText(visibility)) {
            return true;
        }
        String normalizedVisibility = visibility.trim().toUpperCase(Locale.ROOT);
        return !"INTERNAL".equals(normalizedVisibility)
            && !"SECRET".equals(normalizedVisibility)
            && !"SYSTEM".equals(normalizedVisibility);
    }

    private AIActionParamSchema paramSchema(AIActionMetaData metadata, String parameter) {
        if (metadata == null || metadata.getParameterSchemas() == null || metadata.getParameterSchemas().isEmpty() || !StringUtils.hasText(parameter)) {
            return null;
        }
        AIActionParamSchema exact = metadata.getParameterSchemas().get(parameter.trim());
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, AIActionParamSchema> entry : metadata.getParameterSchemas().entrySet()) {
            if (entry != null && StringUtils.hasText(entry.getKey()) && parameter.trim().equalsIgnoreCase(entry.getKey().trim())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private List<Map<String, Object>> buildPriorEvidencePromptModel(List<ExecutedReadAction> executedActions) {
        if (executedActions == null || executedActions.isEmpty()) {
            return List.of();
        }
        return executedActions.stream()
            .map(ExecutedReadAction::toDiagnosticMap)
            .toList();
    }

    private List<String> normalizeVectorSpaces(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            String trimmed = value.trim().toLowerCase(Locale.ROOT);
            if (trimmed.isEmpty() || normalized.contains(trimmed)) {
                continue;
            }
            normalized.add(trimmed);
        }
        return normalized.isEmpty() ? List.of() : List.copyOf(normalized);
    }

    private String writeJson(Object value) {
        try {
            return jsonSupport.objectMapper().writeValueAsString(value);
        } catch (Exception ex) {
            log.debug("Failed to serialize read-action resolution payload: {}", ex.getMessage());
            return "{}";
        }
    }

    private String truncate(String value, int maxChars) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        if (maxChars <= 0 || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, Math.max(0, maxChars - 3)) + "...";
    }

    private String buildExecutionKey(String actionName, Map<String, Object> params) {
        return actionName.trim().toLowerCase(Locale.ROOT) + "::" + writeJson(params != null ? params : Map.of());
    }

    private enum PlannerDecisionType {
        ANSWER_FROM_CONTEXT,
        EXECUTE_READ_ACTIONS,
        EXECUTE_READ_ACTIONS_AND_RAG,
        USE_RAG_ONLY;

        static PlannerDecisionType from(String raw) {
            if (!StringUtils.hasText(raw)) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    private record EligibleReadAction(
        String name,
        AIActionMetaData metadata,
        AIActionHandler handler
    ) {
    }

    private record PlannerActionProposal(
        String name,
        Map<String, Object> params,
        int priority
    ) {
        private Map<String, Object> toDiagnosticMap() {
            return Map.of(
                "name", name,
                "priority", priority,
                "params", params != null ? params : Map.of()
            );
        }
    }

    private record PlannerDecision(
        PlannerDecisionType decision,
        List<PlannerActionProposal> actions,
        Boolean needsMoreSteps,
        String missingEvidenceReason,
        List<String> suggestedVectorSpaces,
        Map<String, Object> diagnostics
    ) {
    }

    private record EvidenceSnippet(
        String summary,
        boolean groundingUsable
    ) {
        private static EvidenceSnippet empty() {
            return new EvidenceSnippet(null, false);
        }
    }

    public record ResolutionOutcome(
        boolean attempted,
        String skipReason,
        PlannerDecisionType decision,
        boolean useRag,
        boolean hasGroundingEvidence,
        String evidenceContext,
        List<String> preferredVectorSpaces,
        List<ExecutedReadAction> executedActions,
        Map<String, Object> diagnostics
    ) {
        public static ResolutionOutcome skipped(String reason) {
            return new ResolutionOutcome(
                false,
                reason,
                PlannerDecisionType.USE_RAG_ONLY,
                true,
                false,
                null,
                List.of(),
                List.of(),
                Map.of("attempted", false, "skipReason", reason)
            );
        }

        public static ResolutionOutcome answerFromActionsOnly(String evidenceContext,
                                                              List<String> preferredVectorSpaces,
                                                              List<ExecutedReadAction> executedActions,
                                                              Map<String, Object> diagnostics) {
            return new ResolutionOutcome(
                true,
                null,
                PlannerDecisionType.EXECUTE_READ_ACTIONS,
                false,
                true,
                evidenceContext,
                preferredVectorSpaces != null ? List.copyOf(preferredVectorSpaces) : List.of(),
                executedActions != null ? List.copyOf(executedActions) : List.of(),
                diagnostics != null ? Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics)) : Map.of("attempted", true)
            );
        }

        public static ResolutionOutcome continueWithRag(String evidenceContext,
                                                        List<String> preferredVectorSpaces,
                                                        List<ExecutedReadAction> executedActions,
                                                        Map<String, Object> diagnostics) {
            boolean hasEvidence = StringUtils.hasText(evidenceContext);
            return new ResolutionOutcome(
                true,
                null,
                hasEvidence ? PlannerDecisionType.EXECUTE_READ_ACTIONS_AND_RAG : PlannerDecisionType.USE_RAG_ONLY,
                true,
                hasEvidence,
                evidenceContext,
                preferredVectorSpaces != null ? List.copyOf(preferredVectorSpaces) : List.of(),
                executedActions != null ? List.copyOf(executedActions) : List.of(),
                diagnostics != null ? Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics)) : Map.of("attempted", true)
            );
        }

        public boolean canAnswerFromActionEvidenceOnly() {
            return attempted && hasGroundingEvidence && !useRag && StringUtils.hasText(evidenceContext);
        }
    }

    public record ExecutedReadAction(
        String actionName,
        Map<String, Object> params,
        AIActionMetaData metadata,
        ActionResult actionResult,
        boolean groundingUsable,
        String evidenceSummary,
        String errorMessage
    ) {
        public static ExecutedReadAction failure(String actionName,
                                                 Map<String, Object> params,
                                                 String errorMessage,
                                                 AIActionMetaData metadata,
                                                 ActionResult actionResult) {
            return new ExecutedReadAction(
                actionName,
                params != null ? Map.copyOf(params) : Map.of(),
                metadata,
                actionResult,
                false,
                null,
                errorMessage
            );
        }

        public Map<String, Object> toDiagnosticMap() {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", actionName);
            item.put("params", params != null ? params : Map.of());
            item.put("groundingUsable", groundingUsable);
            if (metadata != null && StringUtils.hasText(metadata.getCategory())) {
                item.put("category", metadata.getCategory());
            }
            if (actionResult != null) {
                item.put("success", actionResult.isSuccess());
                item.put("message", actionResult.getMessage());
                if (StringUtils.hasText(actionResult.getErrorCode())) {
                    item.put("errorCode", actionResult.getErrorCode());
                }
            }
            if (StringUtils.hasText(evidenceSummary)) {
                item.put("evidenceSummary", evidenceSummary);
            }
            if (StringUtils.hasText(errorMessage)) {
                item.put("failureReason", errorMessage);
            }
            return Collections.unmodifiableMap(item);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PlannerPayload {
        public String decision;
        public List<PlannerActionPayload> actions = List.of();
        public Boolean needsMoreSteps;
        public String missingEvidenceReason;
        public List<String> suggestedVectorSpaces = List.of();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static final class PlannerActionPayload {
        public String name;
        public Map<String, Object> params = Map.of();
        public Integer priority;
    }
}
