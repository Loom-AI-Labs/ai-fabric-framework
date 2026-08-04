package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.extractPromptPreview;
import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.hasManagedGenerationPromptOverride;
import static ai.fabric.intent.orchestration.pipeline.steps.ManagedGenerationPromptSupport.renderManagedGenerationPrompt;

/**
 * Internal post-action generation support for summarizing action results with grounded facts.
 */
@Slf4j
final class PostActionGenerationSupport {

    private static final String ACTION_RELATIONSHIP_QUERY = "relationship_query";
    private static final String DATA_KEY_DATA = "data";
    private static final String DATA_KEY_DOCUMENTS = "documents";

    private static final String READ_ACTION_GENERATION_GROUNDING_INSTRUCTION =
        "Use the read-action facts as the only evidence. If the user's requested conclusion depends on a fact type "
            + "that is absent, say that the evidence is missing. Do not substitute a present fact such as status, "
            + "availability, price, name, or identifier as evidence for a different requested attribute.";

    private static final String TEMPLATE_FAMILY_POST_ACTION_GENERATION = "orchestration/post-action-generation";
    private static final String TEMPLATE_POST_ACTION_SYSTEM = "system";
    private static final String TEMPLATE_POST_ACTION_USER_GENERIC = "user-generic";
    private static final String TEMPLATE_POST_ACTION_USER_GENERIC_MANAGED = "user-generic-managed";
    private static final String TEMPLATE_POST_ACTION_USER_RELATIONSHIP_QUERY = "user-relationship-query";
    private static final String TEMPLATE_POST_ACTION_USER_RELATIONSHIP_QUERY_MANAGED = "user-relationship-query-managed";

    private static final String PLACEHOLDER_ACTION_NAME = "action_name";
    private static final String PLACEHOLDER_INSTRUCTION = "instruction";
    private static final String PLACEHOLDER_FACTS = "facts";
    private static final String PLACEHOLDER_RELATIONAL_QUERY = "relational_query";

    private final AICoreService aiCoreService;
    private final RelationshipQueryPostActionGenerationProperties relationshipQueryPostActionGenerationProperties;
    private final PostActionGenerationProperties postActionGenerationProperties;
    private final ObjectProvider<ObjectMapper> objectMapperProvider;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    PostActionGenerationSupport(AICoreService aiCoreService,
                                RelationshipQueryPostActionGenerationProperties relationshipQueryPostActionGenerationProperties,
                                PostActionGenerationProperties postActionGenerationProperties,
                                ObjectProvider<ObjectMapper> objectMapperProvider,
                                PromptTemplateResolver promptTemplateResolver,
                                PromptRenderer promptRenderer) {
        this.aiCoreService = aiCoreService;
        this.relationshipQueryPostActionGenerationProperties = relationshipQueryPostActionGenerationProperties;
        this.postActionGenerationProperties = postActionGenerationProperties;
        this.objectMapperProvider = objectMapperProvider;
        this.promptTemplateResolver = promptTemplateResolver;
        this.promptRenderer = promptRenderer;
    }

    boolean isReadActionAllowedWhenActionsDisabled(boolean readActionExecutionAllowed,
                                                   AIActionMetaData metadata,
                                                   OrchestrationPolicy policy) {
        return readActionExecutionAllowed
            || shouldForceGroundingEligibleReadActionPostGeneration(metadata, policy);
    }

    ResolvedPostActionGeneration resolvePostActionGeneration(String actionName,
                                                             Intent intent,
                                                             AIActionMetaData metadata,
                                                             OrchestrationPolicy policy,
                                                             boolean readActionExecutionAllowed) {
        boolean isRelationshipQuery = ACTION_RELATIONSHIP_QUERY.equalsIgnoreCase(actionName);
        boolean forceReadActionGeneration = readActionExecutionAllowed
            || shouldForceGroundingEligibleReadActionPostGeneration(metadata, policy);
        if (isRelationshipQuery) {
            if (relationshipQueryPostActionGenerationProperties == null
                || (!relationshipQueryPostActionGenerationProperties.isEnabled() && !forceReadActionGeneration)) {
                return ResolvedPostActionGeneration.disabled();
            }
        } else {
            if (postActionGenerationProperties == null
                || (!postActionGenerationProperties.isEnabled() && !forceReadActionGeneration)) {
                return ResolvedPostActionGeneration.disabled();
            }
        }

        String instructions = null;
        boolean requested = intent != null && Boolean.TRUE.equals(intent.getRequiresGeneration());

        if (intent != null && StringUtils.hasText(intent.getGenerationInstructions())) {
            requested = true;
            instructions = intent.getGenerationInstructions();
        }

        if (forceReadActionGeneration) {
            requested = true;
            if (!StringUtils.hasText(instructions)) {
                instructions = "Answer the user's request from the read-action result facts. If the facts are insufficient, state what is missing instead of inventing details.";
            }
            instructions = appendGenerationInstruction(
                instructions,
                READ_ACTION_GENERATION_GROUNDING_INSTRUCTION
            );
        }

        return new ResolvedPostActionGeneration(requested, instructions, forceReadActionGeneration);
    }

    Optional<Map<String, Object>> buildReadActionGroundingObservation(
        String actionName,
        AIActionHandler handler,
        ActionResult actionResult,
        ActionContext actionContext
    ) {
        if (!StringUtils.hasText(actionName)
            || handler == null
            || actionResult == null
            || !actionResult.isSuccess()) {
            return Optional.empty();
        }

        Map<String, Object> facts = Map.of();
        try {
            Optional<Map<String, Object>> projected =
                handler.buildPostActionLlmFacts(actionResult, actionContext);
            if (projected != null && projected.isPresent()
                && projected.get() != null) {
                facts = projected.get();
            }
        } catch (Exception ex) {
            log.warn(
                "Action handler {} failed to build grounding facts for '{}': {}",
                handler.getClass().getName(),
                actionName,
                ex.getMessage()
            );
        }

        Map<String, Object> groundedFacts =
            includeActionResultDataForForcedReadGeneration(facts, actionResult);
        if (!hasAnswerablePostActionFacts(groundedFacts)) {
            return Optional.empty();
        }

        int configuredMax = postActionGenerationProperties != null
            ? postActionGenerationProperties.getMaxChars()
            : 4_000;
        FactsPayload payload = buildFactsPayload(
            groundedFacts,
            Math.max(1, configuredMax)
        );
        if (!StringUtils.hasText(payload.payload())) {
            return Optional.empty();
        }

        Map<String, Object> observation = new LinkedHashMap<>();
        observation.put("action", actionName.trim());
        observation.put("success", true);
        observation.put("groundingUsable", true);
        observation.put("evidenceSummary", payload.payload());
        observation.put("truncated", payload.truncated());
        return Optional.of(Collections.unmodifiableMap(observation));
    }

    PostActionGenerationOutcome maybeGeneratePostActionSummary(String actionName,
                                                               AIActionHandler handler,
                                                               Intent intent,
                                                               ActionResult actionResult,
                                                               OrchestrationContext context,
                                                               PipelineContext pipelineContext,
                                                               Map<String, Object> actionParams,
                                                               ResolvedPostActionGeneration request) {
        if (request == null || !request.shouldGenerate()) {
            return null;
        }
        if (actionResult == null || !actionResult.isSuccess()) {
            return null;
        }

        if (!ACTION_RELATIONSHIP_QUERY.equalsIgnoreCase(actionName)) {
            return maybeGenerateGenericPostActionSummary(handler, actionName, request, actionResult, context, pipelineContext, actionParams);
        }

        Map<String, Object> actionData = coerceToMap(actionResult.getData());
        Map<String, Object> relationshipData = selectRelationshipActionData(actionData);
        List<?> documents = relationshipData != null ? coerceToList(relationshipData.get(DATA_KEY_DOCUMENTS)) : null;

        int totalResults = coerceToInt(relationshipData != null ? relationshipData.get("totalResults") : null,
            documents != null ? documents.size() : 0);

        if (documents == null || documents.isEmpty()) {
            Map<String, Object> metadata = Map.of(
                "used", false,
                "skippedReason", "no_results",
                "returnedResults", 0,
                "totalResults", totalResults
            );
            String message = StringUtils.hasText(actionResult.getMessage()) ? actionResult.getMessage() : "No results found";
            return new PostActionGenerationOutcome(null, message, metadata);
        }

        FactsPayload facts = buildFactsPayload(documents,
            relationshipQueryPostActionGenerationProperties.getMaxItems(),
            relationshipQueryPostActionGenerationProperties.getMaxChars());

        String instruction = request.generationInstructions();
        if (!StringUtils.hasText(instruction)) {
            instruction = "Summarize the results for the user.";
        }
        if (instruction.length() > 500) {
            instruction = instruction.substring(0, 500);
        }

        String relationalQuery = null;
        Map<String, Object> params = intent.getActionParams();
        if (params != null && params.get("query") != null) {
            relationalQuery = params.get("query").toString();
        } else if (relationshipData != null && relationshipData.get("query") != null) {
            relationalQuery = relationshipData.get("query").toString();
        }

        String systemPrompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_POST_ACTION_GENERATION, TEMPLATE_POST_ACTION_SYSTEM).template(),
            Map.of()
        );

        String userPrompt = buildPostActionUserPrompt(
            instruction,
            relationalQuery,
            facts.payload(),
            extractPromptPreview(pipelineContext)
        );

        AIGenerationRequest generationRequest = AIGenerationRequest.builder()
            .entityId("post-action-" + (pipelineContext != null ? pipelineContext.getRequestId() : UUID.randomUUID()))
            .entityType(ACTION_RELATIONSHIP_QUERY)
            .generationType("relationship_query_post_action_generation")
            .systemPrompt(systemPrompt)
            .prompt(userPrompt)
            .temperature(relationshipQueryPostActionGenerationProperties.getTemperature())
            .maxTokens(800)
            .authContext(context != null ? OrchestrationAuthContextResolver.from(context) : null)
            .build();

        AIGenerationResponse generationResponse;
        try {
            generationResponse = aiCoreService.generateContent(generationRequest, LlmPurpose.GENERATION);
        } catch (Exception ex) {
            Map<String, Object> metadata = postActionGenerationSkippedMetadata(
                "generation_failed",
                ex.getMessage(),
                facts
            );
            if (request.forced()) {
                String fallback = deterministicPostActionSummary(actionName, actionResult, relationshipData);
                if (StringUtils.hasText(fallback) && hasAnswerablePostActionFacts(relationshipData)) {
                    return new PostActionGenerationOutcome(fallback, fallback, withDeterministicFallbackMetadata(metadata));
                }
            }
            String message = StringUtils.hasText(actionResult.getMessage()) ? actionResult.getMessage() : null;
            return new PostActionGenerationOutcome(null, message, metadata);
        }

        String summary = generationResponse != null ? generationResponse.getContent() : null;
        if (!StringUtils.hasText(summary)) {
            Map<String, Object> metadata = postActionGenerationSkippedMetadata(
                "empty_generation_response",
                null,
                facts
            );
            if (request.forced()) {
                String fallback = deterministicPostActionSummary(actionName, actionResult, relationshipData);
                if (StringUtils.hasText(fallback) && hasAnswerablePostActionFacts(relationshipData)) {
                    return new PostActionGenerationOutcome(fallback, fallback, withDeterministicFallbackMetadata(metadata));
                }
            }
            return new PostActionGenerationOutcome(null, StringUtils.hasText(actionResult.getMessage()) ? actionResult.getMessage() : null, metadata);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("used", true);
        metadata.put("purpose", "GENERATION");
        metadata.put("includedItems", facts.includedItems());
        metadata.put("truncated", facts.truncated());
        metadata.put("totalResults", totalResults);
        if (StringUtils.hasText(generationResponse.getModel())) {
            metadata.put("model", generationResponse.getModel());
        }

        String message = summary.trim();
        return new PostActionGenerationOutcome(message, message, Collections.unmodifiableMap(metadata));
    }

    private boolean shouldForceGroundingEligibleReadActionPostGeneration(AIActionMetaData metadata,
                                                                         OrchestrationPolicy policy) {
        return isGroundingEligibleReadAction(metadata)
            && policy != null
            && policy.capabilities() != null
            && policy.capabilities().forceGroundingEligibleReadActionPostGeneration();
    }

    private boolean isGroundingEligibleReadAction(AIActionMetaData metadata) {
        return metadata != null
            && metadata.getAccessMode() == ActionAccessMode.READ
            && metadata.isGroundingEligible();
    }

    private PostActionGenerationOutcome maybeGenerateGenericPostActionSummary(AIActionHandler handler,
                                                                              String actionName,
                                                                              ResolvedPostActionGeneration request,
                                                                              ActionResult actionResult,
                                                                              OrchestrationContext context,
                                                                              PipelineContext pipelineContext,
                                                                              Map<String, Object> actionParams) {
        boolean forced = request != null && request.forced();
        if (handler == null || postActionGenerationProperties == null || (!postActionGenerationProperties.isEnabled() && !forced)) {
            return null;
        }

        Optional<Map<String, Object>> factsOpt;
        try {
            factsOpt = handler.buildPostActionLlmFacts(actionResult, new ActionContext(context, pipelineContext, actionParams));
        } catch (Exception ex) {
            log.warn("Action handler {} failed to build post-action facts for '{}': {}",
                handler.getClass().getName(), actionName, ex.getMessage());
            factsOpt = Optional.empty();
        }
        if (factsOpt == null) {
            factsOpt = Optional.empty();
        }

        Map<String, Object> factsMap = factsOpt.orElse(Map.of());
        if (forced) {
            factsMap = includeActionResultDataForForcedReadGeneration(factsMap, actionResult);
        }
        if (factsMap == null || factsMap.isEmpty()) {
            Map<String, Object> metadata = Map.of(
                "used", false,
                "skippedReason", "handler_opt_out"
            );
            return new PostActionGenerationOutcome(null, actionResult.getMessage(), metadata);
        }
        boolean hasAnswerableFacts = hasAnswerablePostActionFacts(factsMap);

        FactsPayload facts = buildFactsPayload(factsMap, postActionGenerationProperties.getMaxChars());

        String instruction = request != null ? request.generationInstructions() : null;
        if (!StringUtils.hasText(instruction)) {
            instruction = "Summarize the action result for the user.";
        }
        if (instruction.length() > 500) {
            instruction = instruction.substring(0, 500);
        }

        String systemPrompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_POST_ACTION_GENERATION, TEMPLATE_POST_ACTION_SYSTEM).template(),
            Map.of()
        );

        String safeActionName = StringUtils.hasText(actionName) ? actionName.trim() : "(unknown)";
        String safeFacts = facts.payload() != null ? facts.payload() : "";
        String userPrompt = buildGenericPostActionUserPrompt(
            safeActionName,
            instruction,
            safeFacts,
            extractPromptPreview(pipelineContext)
        );

        AIGenerationRequest generationRequest = AIGenerationRequest.builder()
            .entityId("post-action-" + (pipelineContext != null ? pipelineContext.getRequestId() : UUID.randomUUID()))
            .entityType(actionName)
            .generationType("action_post_action_generation")
            .systemPrompt(systemPrompt)
            .prompt(userPrompt)
            .temperature(postActionGenerationProperties.getTemperature())
            .maxTokens(postActionGenerationProperties.getMaxTokens())
            .authContext(context != null ? OrchestrationAuthContextResolver.from(context) : null)
            .build();

        AIGenerationResponse generationResponse;
        try {
            generationResponse = aiCoreService.generateContent(generationRequest, LlmPurpose.GENERATION);
        } catch (Exception ex) {
            Map<String, Object> metadata = postActionGenerationSkippedMetadata(
                "generation_failed",
                ex.getMessage(),
                facts
            );
            if (forced && hasAnswerableFacts) {
                String fallback = deterministicPostActionSummary(actionName, actionResult, factsMap);
                if (StringUtils.hasText(fallback)) {
                    return new PostActionGenerationOutcome(fallback, fallback, withDeterministicFallbackMetadata(metadata));
                }
            }
            return new PostActionGenerationOutcome(null, actionResult.getMessage(), metadata);
        }

        String summary = generationResponse != null ? generationResponse.getContent() : null;
        if (!StringUtils.hasText(summary)) {
            Map<String, Object> metadata = postActionGenerationSkippedMetadata(
                "empty_generation_response",
                null,
                facts
            );
            if (forced && hasAnswerableFacts) {
                String fallback = deterministicPostActionSummary(actionName, actionResult, factsMap);
                if (StringUtils.hasText(fallback)) {
                    return new PostActionGenerationOutcome(fallback, fallback, withDeterministicFallbackMetadata(metadata));
                }
            }
            return new PostActionGenerationOutcome(null, actionResult.getMessage(), metadata);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("used", true);
        metadata.put("purpose", "GENERATION");
        metadata.put("action", actionName);
        metadata.put("includedItems", facts.includedItems());
        metadata.put("truncated", facts.truncated());
        if (StringUtils.hasText(generationResponse.getModel())) {
            metadata.put("model", generationResponse.getModel());
        }

        String message = summary.trim();
        return new PostActionGenerationOutcome(message, message, Collections.unmodifiableMap(metadata));
    }

    private String appendGenerationInstruction(String existing, String addition) {
        if (!StringUtils.hasText(addition)) {
            return StringUtils.hasText(existing) ? existing.trim() : null;
        }
        if (!StringUtils.hasText(existing)) {
            return addition.trim();
        }
        String trimmedExisting = existing.trim();
        String trimmedAddition = addition.trim();
        if (trimmedExisting.contains(trimmedAddition)) {
            return trimmedExisting;
        }
        return trimmedExisting + "\n\nEvidence contract: " + trimmedAddition;
    }

    private Map<String, Object> includeActionResultDataForForcedReadGeneration(Map<String, Object> factsMap,
                                                                               ActionResult actionResult) {
        Map<String, Object> actionResultData = actionResult != null ? coerceToMap(actionResult.getData()) : null;
        if (actionResultData == null || actionResultData.isEmpty()) {
            return factsMap;
        }
        Map<String, Object> merged = new LinkedHashMap<>(factsMap != null ? factsMap : Map.of());
        merged.put("actionResultData", Collections.unmodifiableMap(new LinkedHashMap<>(actionResultData)));
        return Collections.unmodifiableMap(merged);
    }

    private Map<String, Object> postActionGenerationSkippedMetadata(String skippedReason,
                                                                    String error,
                                                                    FactsPayload facts) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("used", false);
        metadata.put("skippedReason", StringUtils.hasText(skippedReason) ? skippedReason : "unknown");
        if (StringUtils.hasText(error)) {
            metadata.put("error", error);
        }
        if (facts != null) {
            metadata.put("includedItems", facts.includedItems());
            metadata.put("truncated", facts.truncated());
        }
        return Collections.unmodifiableMap(metadata);
    }

    private Map<String, Object> withDeterministicFallbackMetadata(Map<String, Object> metadata) {
        Map<String, Object> updated = new LinkedHashMap<>(metadata != null ? metadata : Map.of());
        updated.put("deterministicFallbackUsed", true);
        updated.put("deterministicFallbackSource", "post_action_facts");
        return Collections.unmodifiableMap(updated);
    }

    private boolean hasAnswerablePostActionFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey())) {
                continue;
            }
            String key = entry.getKey().trim();
            if (isBasePostActionFactKey(key)) {
                continue;
            }
            if (hasMeaningfulPostActionFactValue(entry.getValue())) {
                return true;
            }
        }
        return false;
    }

    private boolean isBasePostActionFactKey(String key) {
        if (!StringUtils.hasText(key)) {
            return true;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        return "action".equals(normalized)
            || "category".equals(normalized)
            || "success".equals(normalized)
            || "message".equals(normalized)
            || "errorcode".equals(normalized);
    }

    private boolean hasMeaningfulPostActionFactValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof CharSequence text) {
            return StringUtils.hasText(text.toString());
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(this::hasMeaningfulPostActionFactValue);
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (hasMeaningfulPostActionFactValue(item)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private String deterministicPostActionSummary(String actionName,
                                                  ActionResult actionResult,
                                                  Map<String, Object> facts) {
        if (!hasAnswerablePostActionFacts(facts)) {
            return null;
        }

        List<String> countFacts = collectPostActionCountFacts(facts);
        List<String> scalarFacts = collectPostActionScalarFacts(facts);

        StringBuilder summary = new StringBuilder();
        String safeActionName = StringUtils.hasText(actionName) ? actionName.trim() : "the read action";
        summary.append("The read action ").append(safeActionName).append(" returned ");
        if (!countFacts.isEmpty()) {
            summary.append(joinHumanList(countFacts)).append(".");
        } else if (!scalarFacts.isEmpty()) {
            summary.append(joinHumanList(scalarFacts)).append(".");
        } else {
            summary.append("grounded evidence.");
        }

        if (!scalarFacts.isEmpty() && !countFacts.isEmpty()) {
            summary.append(" Key facts: ").append(joinHumanList(scalarFacts)).append(".");
        } else if (StringUtils.hasText(actionResult != null ? actionResult.getMessage() : null)
            && !isGenericActionResultMessage(actionResult.getMessage())) {
            summary.append(" ").append(actionResult.getMessage().trim());
        }

        return truncatePostActionSummary(summary.toString());
    }

    private List<String> collectPostActionCountFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<String> counts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey()) || counts.size() >= 5) {
                continue;
            }
            String key = entry.getKey().trim();
            if (isBasePostActionFactKey(key)) {
                continue;
            }
            if (key.endsWith("Count") && entry.getValue() instanceof Number number) {
                counts.add(formatCountFact(key.substring(0, key.length() - "Count".length()), number));
            } else if ("count".equalsIgnoreCase(key) && entry.getValue() instanceof Number number) {
                counts.add(formatCountFact("result", number));
            }
        }
        return counts.isEmpty() ? List.of() : List.copyOf(counts);
    }

    private String formatCountFact(String key, Number number) {
        String label = humanizePostActionKey(key);
        long value = number.longValue();
        return value + " " + pluralizePostActionLabel(label, value);
    }

    private List<String> collectPostActionScalarFacts(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) {
            return List.of();
        }
        List<String> scalars = new ArrayList<>();
        for (Map.Entry<String, Object> entry : facts.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey()) || scalars.size() >= 4) {
                continue;
            }
            String key = entry.getKey().trim();
            if (isBasePostActionFactKey(key) || key.endsWith("Count")) {
                continue;
            }
            Object value = entry.getValue();
            if (value instanceof Map<?, ?> || value instanceof Iterable<?>) {
                continue;
            }
            if (value != null && StringUtils.hasText(value.toString())) {
                scalars.add(humanizePostActionKey(key) + "=" + truncatePostActionValue(value.toString()));
            }
        }
        return scalars.isEmpty() ? List.of() : List.copyOf(scalars);
    }

    private String humanizePostActionKey(String key) {
        if (!StringUtils.hasText(key)) {
            return "results";
        }
        String spaced = key.trim()
            .replace('_', ' ')
            .replace('-', ' ')
            .replaceAll("(?<=[a-z0-9])(?=[A-Z])", " ")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
        return StringUtils.hasText(spaced) ? spaced : "results";
    }

    private String pluralizePostActionLabel(String label, long count) {
        if (!StringUtils.hasText(label)) {
            return count == 1 ? "result" : "results";
        }
        if (count == 1 || label.endsWith("s")) {
            return label;
        }
        return label + "s";
    }

    private String joinHumanList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        if (values.size() == 1) {
            return values.getFirst();
        }
        if (values.size() == 2) {
            return values.get(0) + " and " + values.get(1);
        }
        return String.join(", ", values.subList(0, values.size() - 1)) + ", and " + values.getLast();
    }

    private boolean isGenericActionResultMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return true;
        }
        String normalized = message.trim().toLowerCase(Locale.ROOT);
        return "ok".equals(normalized)
            || "success".equals(normalized)
            || "action executed".equals(normalized)
            || "action executed.".equals(normalized)
            || "mcp tool result".equals(normalized);
    }

    private String truncatePostActionValue(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() > 80 ? normalized.substring(0, 77) + "..." : normalized;
    }

    private String truncatePostActionSummary(String value) {
        if (!StringUtils.hasText(value)) {
            return value;
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() > 600 ? normalized.substring(0, 597) + "..." : normalized;
    }

    private Map<String, Object> coerceToMap(Object value) {
        if (value instanceof ActionPayload payload) {
            return payload.toMap();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null) {
                    result.put(key.toString(), item);
                }
            });
            return result;
        }
        return null;
    }

    private Map<String, Object> selectRelationshipActionData(Map<String, Object> actionData) {
        if (actionData == null || actionData.isEmpty()) {
            return actionData;
        }
        Map<String, Object> nested = coerceToMap(actionData.get(DATA_KEY_DATA));
        if (hasRelationshipResultShape(nested)) {
            return nested;
        }
        return actionData;
    }

    private boolean hasRelationshipResultShape(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return false;
        }
        return data.containsKey(DATA_KEY_DOCUMENTS)
            || data.containsKey("totalResults")
            || data.containsKey("returnedResults")
            || data.containsKey("query");
    }

    private List<?> coerceToList(Object value) {
        if (value instanceof List<?> list) {
            return list;
        }
        return null;
    }

    private int coerceToInt(Object value, int fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private FactsPayload buildFactsPayload(Map<String, Object> facts, int maxChars) {
        if (facts == null) {
            return new FactsPayload("(no facts)", 0, false);
        }

        String payload;
        try {
            ObjectMapper mapper = objectMapperProvider != null ? objectMapperProvider.getIfAvailable() : null;
            if (mapper != null) {
                payload = mapper.writeValueAsString(facts);
            } else {
                log.debug("No ObjectMapper available; falling back to Map.toString() for post-action facts payload");
                payload = facts.toString();
            }
        } catch (Exception ex) {
            payload = facts.toString();
        }

        boolean truncated = false;
        String normalized = StringUtils.hasText(payload) ? payload.trim() : "";
        if (StringUtils.hasText(normalized) && normalized.length() > maxChars) {
            normalized = normalized.substring(0, maxChars);
            truncated = true;
        }
        if (!StringUtils.hasText(normalized)) {
            normalized = "(no serializable facts)";
        }

        return new FactsPayload(normalized, facts.size(), truncated);
    }

    private FactsPayload buildFactsPayload(List<?> documents, int maxItems, int maxChars) {
        int limit = Math.min(Math.max(1, maxItems), documents.size());
        StringBuilder builder = new StringBuilder(Math.min(maxChars, 2048));

        int included = 0;
        boolean truncated = false;
        for (int i = 0; i < limit; i++) {
            Object doc = documents.get(i);
            String line = formatDocumentFact(i + 1, doc);
            if (!StringUtils.hasText(line)) {
                continue;
            }
            if (builder.length() + line.length() + 1 > maxChars) {
                truncated = true;
                break;
            }
            builder.append(line).append('\n');
            included++;
        }

        String payload = builder.toString().trim();
        if (!StringUtils.hasText(payload)) {
            payload = "(no serializable facts)";
        }
        return new FactsPayload(payload, included, truncated);
    }

    private String formatDocumentFact(int index, Object document) {
        String id = readProperty(document, "id");
        String content = readProperty(document, "content");
        String metadata = readProperty(document, "metadata");

        StringBuilder line = new StringBuilder();
        line.append(index).append(") ");
        if (StringUtils.hasText(id)) {
            line.append("id=").append(id).append(" ");
        }
        if (StringUtils.hasText(metadata)) {
            line.append("metadata=").append(metadata).append(" ");
        }
        if (StringUtils.hasText(content)) {
            line.append("content=").append(content);
        }

        String rendered = line.toString().trim();
        return rendered.isEmpty() ? null : rendered;
    }

    private String readProperty(Object target, String property) {
        if (target == null || !StringUtils.hasText(property)) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            Object value = map.get(property);
            return value != null ? value.toString() : null;
        }
        try {
            String method = "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1);
            var reflected = target.getClass().getMethod(method);
            Object value = reflected.invoke(target);
            return value != null ? value.toString() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildPostActionUserPrompt(String instruction,
                                             String relationalQuery,
                                             String facts,
                                             Map<String, String> promptOverlay) {
        String queryPart = StringUtils.hasText(relationalQuery) ? relationalQuery : "(unknown)";
        String safeInstruction = StringUtils.hasText(instruction) ? instruction.trim() : "Summarize the results for the user.";
        String safeFacts = facts != null ? facts : "";

        if (hasManagedGenerationPromptOverride(promptOverlay)) {
            return renderManagedGenerationPrompt(
                promptTemplateResolver,
                promptRenderer,
                TEMPLATE_FAMILY_POST_ACTION_GENERATION,
                TEMPLATE_POST_ACTION_USER_RELATIONSHIP_QUERY_MANAGED,
                Map.of(
                    PLACEHOLDER_INSTRUCTION, safeInstruction,
                    PLACEHOLDER_RELATIONAL_QUERY, queryPart,
                    PLACEHOLDER_FACTS, safeFacts
                ),
                promptOverlay
            );
        }

        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_POST_ACTION_GENERATION, TEMPLATE_POST_ACTION_USER_RELATIONSHIP_QUERY).template(),
            Map.of(
                PLACEHOLDER_INSTRUCTION, safeInstruction,
                PLACEHOLDER_RELATIONAL_QUERY, queryPart,
                PLACEHOLDER_FACTS, safeFacts
            )
        );
    }

    private String buildGenericPostActionUserPrompt(String actionName,
                                                    String instruction,
                                                    String facts,
                                                    Map<String, String> promptOverlay) {
        if (hasManagedGenerationPromptOverride(promptOverlay)) {
            return renderManagedGenerationPrompt(
                promptTemplateResolver,
                promptRenderer,
                TEMPLATE_FAMILY_POST_ACTION_GENERATION,
                TEMPLATE_POST_ACTION_USER_GENERIC_MANAGED,
                Map.of(
                    PLACEHOLDER_ACTION_NAME, actionName,
                    PLACEHOLDER_INSTRUCTION, instruction,
                    PLACEHOLDER_FACTS, facts
                ),
                promptOverlay
            );
        }

        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_POST_ACTION_GENERATION, TEMPLATE_POST_ACTION_USER_GENERIC).template(),
            Map.of(
                PLACEHOLDER_ACTION_NAME, actionName,
                PLACEHOLDER_INSTRUCTION, instruction,
                PLACEHOLDER_FACTS, facts
            )
        );
    }

    record FactsPayload(String payload, int includedItems, boolean truncated) {
    }

    record PostActionGenerationOutcome(String summary, String message, Map<String, Object> metadata) {
    }

    record ResolvedPostActionGeneration(boolean shouldGenerate, String generationInstructions, boolean forced) {
        static ResolvedPostActionGeneration disabled() {
            return new ResolvedPostActionGeneration(false, null, false);
        }
    }
}
