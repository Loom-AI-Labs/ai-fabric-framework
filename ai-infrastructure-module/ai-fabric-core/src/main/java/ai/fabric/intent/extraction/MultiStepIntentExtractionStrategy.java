package ai.fabric.intent.extraction;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.intent.OrchestrationPolicyPromptConstraints;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Multi-step extraction fallback that decomposes the task into smaller prompts.
 *
 * <p>Prompt design is intentionally conservative and hard-constrains ACTION intents to the registered action list.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MultiStepIntentExtractionStrategy implements IntentExtractionStrategy {

    private static final String ENTITY_TYPE = "intent_extraction";
    private static final String GENERATION_TYPE = "intent_extraction_multi_step";

    private static final String TEMPLATE_FAMILY = "intent-extraction/multi-step";
    private static final String TEMPLATE_SYSTEM = "system";
    private static final String TEMPLATE_CLASSIFY = "classify";
    private static final String TEMPLATE_SELECT_ACTIONS = "select-actions";
    private static final String TEMPLATE_FILL_PARAMS = "fill-params";

    private static final String PLACEHOLDER_USER_QUERY = "user_query";
    private static final String PLACEHOLDER_ALLOWED_ACTIONS = "allowed_actions";
    private static final String PLACEHOLDER_ACTION_INTENTS = "action_intents";
    private static final String PLACEHOLDER_ACTION_SPECS = "action_specs";
    private static final String PLACEHOLDER_TASKS = "tasks";
    private static final String RESPONSE_METADATA_KEY_EXTRACTION_MODE = "extractionMode";
    private static final String RESPONSE_METADATA_VALUE_MULTI_STEP = "multi_step";
    private static final String RESPONSE_METADATA_KEY_RETRIEVAL_QUERY_HINT = "retrievalQueryHint";

    private final AICoreService aiCoreService;
    private final AIActionRegistry actionHandlerRegistry;
    private final IntentExtractionJsonSupport jsonSupport;
    private final IntentExtractionValidator validator;
    private final PromptRenderer promptRenderer;
    private final PromptTemplateResolver promptTemplateResolver;

    record ClassificationResult(ClassificationResponse response,
                                int llmCalls,
                                Long processingTimeMs,
                                Long providerProcessingTimeMs,
                                String model) {}
    record ActionSelectionResult(Map<Integer, String> mappings,
                                 int llmCalls,
                                 Long processingTimeMs,
                                 Long providerProcessingTimeMs,
                                 String model) {}
    record ActionParamsFillResult(Map<Integer, Map<String, Object>> paramsByIntentIndex,
                                  int llmCalls,
                                  Long processingTimeMs,
                                  Long providerProcessingTimeMs,
                                  String model) {}

    static class LlmCallFailureException extends RuntimeException {
        private final int llmCalls;

        LlmCallFailureException(int llmCalls, Exception cause) {
            super(cause != null ? cause.getMessage() : "LLM call failed", cause);
            this.llmCalls = llmCalls;
        }

        int llmCalls() {
            return llmCalls;
        }
    }

    @PostConstruct
    void validatePromptTemplates() {
        renderTemplate(TEMPLATE_SYSTEM, Map.of());
        renderTemplate(TEMPLATE_CLASSIFY, Map.of(PLACEHOLDER_USER_QUERY, "test"));
        renderTemplate(TEMPLATE_SELECT_ACTIONS, Map.of(
            PLACEHOLDER_ALLOWED_ACTIONS, "- example_action: Example",
            PLACEHOLDER_ACTION_INTENTS, "- intentIndex=0 actionHint=example\n",
            PLACEHOLDER_USER_QUERY, "test"
        ));
        renderTemplate(TEMPLATE_FILL_PARAMS, Map.of(
            PLACEHOLDER_ACTION_SPECS, "- action=example\n  required: (none)\n",
            PLACEHOLDER_TASKS, "- intentIndex=0 action=example requiredParams=[] allowedParams=[]\n",
            PLACEHOLDER_USER_QUERY, "test"
        ));
    }

    @Override
    public ExtractionAttempt attemptExtract(IntentExtractionInput input, OrchestrationContext context) {
        String userQuery = input != null ? input.userQuery() : null;
        String currentUserMessage = input != null && StringUtils.hasText(input.currentUserMessage())
            ? input.currentUserMessage()
            : userQuery;

        int llmCalls = 0;
        try {
            ClassificationResult classificationResult = classify(currentUserMessage, input, context);
            llmCalls += classificationResult.llmCalls();
            ClassificationResponse classification = classificationResult.response();
            if (classification == null || CollectionUtils.isEmpty(classification.getIntents())) {
                return ExtractionAttempt.builder()
                    .success(false)
                    .strategyName(getStrategyName())
                    .errorMessage("Multi-step classification returned no intents")
                    .llmCalls(llmCalls)
                    .build();
            }

            ActionSelectionResult selection = selectActionsIfNeeded(currentUserMessage, input, context, classification);
            llmCalls += selection.llmCalls();
            Map<Integer, String> selectedActions = selection.mappings();

            ActionParamsFillResult paramFill = fillActionParamsIfNeeded(currentUserMessage, input, context, classification, selectedActions);
            llmCalls += paramFill.llmCalls();

            MultiIntentResponse response = buildResponse(userQuery, classification, selectedActions, paramFill.paramsByIntentIndex());
            IntentExtractionValidator.ValidationResult validation = validator.validate(response);

            return ExtractionAttempt.builder()
                .success(validation.valid())
                .response(response)
                .validationResult(validation)
                .strategyName(getStrategyName())
                .llmCalls(llmCalls)
                .processingTimeMs(sumNonNull(
                    classificationResult.processingTimeMs(),
                    selection.processingTimeMs(),
                    paramFill.processingTimeMs()
                ))
                .providerProcessingTimeMs(sumNonNull(
                    classificationResult.providerProcessingTimeMs(),
                    selection.providerProcessingTimeMs(),
                    paramFill.providerProcessingTimeMs()
                ))
                .model(joinDistinctModels(
                    classificationResult.model(),
                    selection.model(),
                    paramFill.model()
                ))
                .build();
        } catch (LlmCallFailureException ex) {
            llmCalls += ex.llmCalls();
            log.warn("Multi-step extraction failed during LLM call: {}", ex.getMessage());
            return ExtractionAttempt.builder()
                .success(false)
                .strategyName(getStrategyName())
                .errorMessage(ex.getMessage())
                .exception(ex)
                .llmCalls(llmCalls)
                .build();
        } catch (Exception ex) {
            log.warn("Multi-step extraction failed: {}", ex.getMessage());
            return ExtractionAttempt.builder()
                .success(false)
                .strategyName(getStrategyName())
                .errorMessage(ex.getMessage())
                .exception(ex)
                .llmCalls(llmCalls)
                .build();
        }
    }

    @Override
    public String getStrategyName() {
        return "multi_step";
    }

    private ClassificationResult classify(String currentUserMessage, IntentExtractionInput input, OrchestrationContext context) {
        String prompt = renderTemplate(TEMPLATE_CLASSIFY, Map.of(
            PLACEHOLDER_USER_QUERY, currentUserMessage
        ));

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("intent-" + UUID.randomUUID())
            .entityType(ENTITY_TYPE)
            .generationType(GENERATION_TYPE + "_classify")
            .systemPrompt(buildSystemPrompt(context))
            .prompt(prompt)
            .messages(input != null ? input.historyMessages() : List.of())
            .parameters(jsonSupport.jsonOnlyResponseParameters())
            .authContext(OrchestrationAuthContextResolver.from(context != null ? context : OrchestrationContext.anonymous()))
            .build();

        int llmCalls = 1;
        long startNanos = System.nanoTime();
        AIGenerationResponse response;
        try {
            response = aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
        } catch (Exception ex) {
            throw new LlmCallFailureException(llmCalls, ex);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        String content = response != null ? response.getContent() : null;
        if (!StringUtils.hasText(content)) {
            return new ClassificationResult(
                null,
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }
        try {
            return new ClassificationResult(
                jsonSupport.parsePayload(content, ClassificationResponse.class),
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        } catch (Exception ex) {
            log.warn("Failed to parse multi-step classification JSON: {}", ex.getMessage());
            return new ClassificationResult(
                null,
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }
    }

    private ActionSelectionResult selectActionsIfNeeded(String query,
                                                        IntentExtractionInput input,
                                                        OrchestrationContext context,
                                                        ClassificationResponse classification) {
        List<ClassificationIntent> actionIntents = new ArrayList<>();
        for (int i = 0; i < classification.getIntents().size(); i++) {
            ClassificationIntent intent = classification.getIntents().get(i);
            if (intent != null && IntentType.ACTION.name().equalsIgnoreCase(intent.getType())) {
                actionIntents.add(intent.withIndex(i));
            }
        }

        if (actionIntents.isEmpty()) {
            return new ActionSelectionResult(Map.of(), 0, null, null, null);
        }

        List<AIActionMetaData> actions = actionHandlerRegistry != null ? actionHandlerRegistry.getAllMetadata() : List.of();
        if (actions.isEmpty()) {
            return new ActionSelectionResult(Map.of(), 0, null, null, null);
        }

        Map<String, String> allowedByNormalizedName = new LinkedHashMap<>();
        for (AIActionMetaData meta : actions) {
            if (meta == null || !StringUtils.hasText(meta.getName())) {
                continue;
            }
            allowedByNormalizedName.put(normalizeActionName(meta.getName()), meta.getName());
        }

        String actionsList = actions.stream()
            .map(meta -> "- " + meta.getName() + (StringUtils.hasText(meta.getDescription()) ? ": " + meta.getDescription() : ""))
            .collect(Collectors.joining("\n"));

        StringBuilder actionHints = new StringBuilder();
        for (ClassificationIntent intent : actionIntents) {
            actionHints.append("- intentIndex=").append(intent.getIndex())
                .append(" actionHint=").append(intent.getActionHint() != null ? intent.getActionHint() : "")
                .append("\n");
        }

        String prompt = renderTemplate(TEMPLATE_SELECT_ACTIONS, Map.of(
            PLACEHOLDER_ALLOWED_ACTIONS, actionsList,
            PLACEHOLDER_ACTION_INTENTS, actionHints.toString(),
            PLACEHOLDER_USER_QUERY, query
        ));

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("intent-" + UUID.randomUUID())
            .entityType(ENTITY_TYPE)
            .generationType(GENERATION_TYPE + "_select_actions")
            .systemPrompt(buildSystemPrompt(context))
            .prompt(prompt)
            .messages(input != null ? input.historyMessages() : List.of())
            .parameters(jsonSupport.jsonOnlyResponseParameters())
            .authContext(OrchestrationAuthContextResolver.from(context != null ? context : OrchestrationContext.anonymous()))
            .build();

        int llmCalls = 1;
        long startNanos = System.nanoTime();
        AIGenerationResponse response;
        try {
            response = aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
        } catch (Exception ex) {
            throw new LlmCallFailureException(llmCalls, ex);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        String content = response != null ? response.getContent() : null;
        if (!StringUtils.hasText(content)) {
            return new ActionSelectionResult(
                Map.of(),
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }

        ActionSelectionResponse parsed;
        try {
            parsed = jsonSupport.parsePayload(content, ActionSelectionResponse.class);
        } catch (Exception ex) {
            log.warn("Failed to parse action selection JSON: {}", ex.getMessage());
            return new ActionSelectionResult(
                Map.of(),
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }

        if (parsed == null || CollectionUtils.isEmpty(parsed.getMappings())) {
            return new ActionSelectionResult(
                Map.of(),
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }

        Map<Integer, String> out = new LinkedHashMap<>();
        for (ActionMapping mapping : parsed.getMappings()) {
            if (mapping == null) {
                continue;
            }
            Integer idx = mapping.getIntentIndex();
            if (idx == null || idx < 0) {
                continue;
            }
            String normalizedSelected = normalizeActionName(mapping.getSelectedAction());
            String canonical = normalizedSelected != null ? allowedByNormalizedName.get(normalizedSelected) : null;
            if (canonical != null) {
                out.put(idx, canonical);
            }
        }
        return new ActionSelectionResult(
            Collections.unmodifiableMap(out),
            llmCalls,
            elapsedMs,
            response != null ? response.getProcessingTimeMs() : null,
            response != null ? response.getModel() : null
        );
    }

    private ActionParamsFillResult fillActionParamsIfNeeded(String query,
                                                            IntentExtractionInput input,
                                                            OrchestrationContext context,
                                                            ClassificationResponse classification,
                                                            Map<Integer, String> selectedActions) {
        if (classification == null || CollectionUtils.isEmpty(classification.getIntents())) {
            return new ActionParamsFillResult(Map.of(), 0, null, null, null);
        }
        if (selectedActions == null || selectedActions.isEmpty()) {
            return new ActionParamsFillResult(Map.of(), 0, null, null, null);
        }

        List<AIActionMetaData> actions = actionHandlerRegistry != null ? actionHandlerRegistry.getAllMetadata() : List.of();
        if (actions.isEmpty()) {
            return new ActionParamsFillResult(Map.of(), 0, null, null, null);
        }

        Map<String, AIActionMetaData> metadataByName = new LinkedHashMap<>();
        for (AIActionMetaData meta : actions) {
            if (meta == null || !StringUtils.hasText(meta.getName())) {
                continue;
            }
            metadataByName.put(meta.getName(), meta);
        }

        StringBuilder tasks = new StringBuilder();
        for (Map.Entry<Integer, String> entry : selectedActions.entrySet()) {
            Integer index = entry.getKey();
            String action = entry.getValue();
            if (index == null || index < 0 || !StringUtils.hasText(action)) {
                continue;
            }

            AIActionMetaData meta = metadataByName.get(action);
            Map<String, String> params = meta != null ? meta.getParameters() : Map.of();
            if ((meta == null || meta.getRequiredParameters() == null || meta.getRequiredParameters().isEmpty())
                && (params == null || params.isEmpty())) {
                continue;
            }
            String required = meta != null && meta.getRequiredParameters() != null && !meta.getRequiredParameters().isEmpty()
                ? String.join(", ", meta.getRequiredParameters())
                : "";

            tasks.append("- intentIndex=").append(index)
                .append(" action=").append(action)
                .append(" requiredParams=[").append(required).append("]")
                .append(" allowedParams=").append(params != null ? params.keySet() : Set.of())
                .append("\n");
        }

        if (tasks.isEmpty()) {
            return new ActionParamsFillResult(Map.of(), 0, null, null, null);
        }

        StringBuilder specs = new StringBuilder();
        for (AIActionMetaData meta : actions) {
            if (meta == null || !StringUtils.hasText(meta.getName())) {
                continue;
            }
            specs.append("- action=").append(meta.getName()).append("\n");
            if (meta.getRequiredParameters() != null && !meta.getRequiredParameters().isEmpty()) {
                specs.append("  required: ").append(String.join(", ", meta.getRequiredParameters())).append("\n");
            } else {
                specs.append("  required: (none)\n");
            }
            if (meta.getParameterSchemas() != null && !meta.getParameterSchemas().isEmpty()) {
                specs.append("  paramsSchema:\n");
                for (Map.Entry<String, AIActionParamSchema> entry : meta.getParameterSchemas().entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    specs.append("    ").append(entry.getKey()).append(": ").append(summarizeSchema(entry.getValue(), 0)).append("\n");
                }
            } else if (meta.getParameters() != null && !meta.getParameters().isEmpty()) {
                for (Map.Entry<String, String> param : meta.getParameters().entrySet()) {
                    if (param.getKey() == null) {
                        continue;
                    }
                    specs.append("  - ").append(param.getKey()).append(": ").append(param.getValue() != null ? param.getValue() : "").append("\n");
                }
            }
        }

        String prompt = renderTemplate(TEMPLATE_FILL_PARAMS, Map.of(
            PLACEHOLDER_ACTION_SPECS, specs.toString(),
            PLACEHOLDER_TASKS, tasks.toString(),
            PLACEHOLDER_USER_QUERY, query
        ));

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("intent-" + UUID.randomUUID())
            .entityType(ENTITY_TYPE)
            .generationType(GENERATION_TYPE + "_fill_params")
            .systemPrompt(buildSystemPrompt(context))
            .prompt(prompt)
            .messages(input != null ? input.historyMessages() : List.of())
            .parameters(jsonSupport.jsonOnlyResponseParameters())
            .authContext(OrchestrationAuthContextResolver.from(context != null ? context : OrchestrationContext.anonymous()))
            .build();

        int llmCalls = 1;
        long startNanos = System.nanoTime();
        AIGenerationResponse response;
        try {
            response = aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
        } catch (Exception ex) {
            throw new LlmCallFailureException(llmCalls, ex);
        }
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        String content = response != null ? response.getContent() : null;
        if (!StringUtils.hasText(content)) {
            return new ActionParamsFillResult(
                Map.of(),
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }

        ActionParamsFillResponse parsed;
        try {
            parsed = jsonSupport.parsePayload(content, ActionParamsFillResponse.class);
        } catch (Exception ex) {
            log.warn("Failed to parse actionParams fill JSON: {}", ex.getMessage());
            return new ActionParamsFillResult(
                Map.of(),
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }

        if (parsed == null || CollectionUtils.isEmpty(parsed.getMappings())) {
            return new ActionParamsFillResult(
                Map.of(),
                llmCalls,
                elapsedMs,
                response != null ? response.getProcessingTimeMs() : null,
                response != null ? response.getModel() : null
            );
        }

        Map<Integer, Map<String, Object>> out = new LinkedHashMap<>();
        for (ActionParamsMapping mapping : parsed.getMappings()) {
            if (mapping == null || mapping.getIntentIndex() == null || mapping.getIntentIndex() < 0) {
                continue;
            }
            if (mapping.getActionParams() == null || mapping.getActionParams().isEmpty()) {
                continue;
            }
            Map<String, Object> cleaned = new LinkedHashMap<>();
            mapping.getActionParams().forEach((key, value) -> {
                if (!StringUtils.hasText(key) || value == null) {
                    return;
                }
                cleaned.put(key, value);
            });
            if (!cleaned.isEmpty()) {
                out.put(mapping.getIntentIndex(), Collections.unmodifiableMap(cleaned));
            }
        }

        return new ActionParamsFillResult(
            Collections.unmodifiableMap(out),
            llmCalls,
            elapsedMs,
            response != null ? response.getProcessingTimeMs() : null,
            response != null ? response.getModel() : null
        );
    }

    private String renderTemplate(String name, Map<String, String> values) {
        return promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, name).template(),
            values
        );
    }

    private Long sumNonNull(Long... values) {
        long total = 0L;
        boolean found = false;
        if (values != null) {
            for (Long value : values) {
                if (value != null) {
                    total += value;
                    found = true;
                }
            }
        }
        return found ? total : null;
    }

    private String joinDistinctModels(String... values) {
        if (values == null || values.length == 0) {
            return null;
        }
        List<String> models = new ArrayList<>();
        for (String value : values) {
            if (StringUtils.hasText(value) && !models.contains(value)) {
                models.add(value);
            }
        }
        return models.isEmpty() ? null : String.join(", ", models);
    }

    private String buildSystemPrompt(OrchestrationContext context) {
        String base = renderTemplate(TEMPLATE_SYSTEM, Map.of());
        String addon = OrchestrationPolicyPromptConstraints.buildSystemAddon(
            context != null ? context.getOrchestrationPolicy() : null
        );
        if (!StringUtils.hasText(addon)) {
            return base;
        }
        return (StringUtils.hasText(base) ? base.trim() + "\n\n" : "") + addon;
    }

    private String normalizeActionName(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9_]+", "_");
        normalized = normalized.replaceAll("_+", "_");
        normalized = normalized.replaceAll("^_+|_+$", "");
        if (normalized.endsWith("_action")) {
            normalized = normalized.substring(0, normalized.length() - "_action".length());
        }
        if (normalized.isBlank() || "null".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized;
    }

    private String summarizeSchema(AIActionParamSchema schema, int depth) {
        if (schema == null || depth > 3) {
            return "unknown";
        }
        AIActionParamType type = schema.getType() != null ? schema.getType() : AIActionParamType.UNKNOWN;
        String suffix = Boolean.TRUE.equals(schema.getRequired()) ? "!" : "";
        String batch = Boolean.TRUE.equals(schema.getBatchTargets()) ? " [batchTargets]" : "";
        String defaultValue = schema.getDefaultValue() != null ? " default=" + schema.getDefaultValue() : "";

        String summary = switch (type) {
            case STRING -> "string" + suffix + batch + defaultValue;
            case INTEGER -> "integer" + suffix + batch + defaultValue;
            case NUMBER -> "number" + suffix + batch + defaultValue;
            case BOOLEAN -> "boolean" + suffix + batch + defaultValue;
            case ARRAY -> {
                String item = schema.getItems() != null ? summarizeSchema(schema.getItems(), depth + 1) : "unknown";
                yield "array<" + item + ">" + suffix + batch + defaultValue;
            }
            case OBJECT -> {
                if (schema.getProperties() != null && !schema.getProperties().isEmpty()) {
                    String props = schema.getProperties().entrySet().stream()
                        .filter(e -> e.getKey() != null)
                        .limit(12)
                        .map(e -> e.getKey() + ":" + summarizeSchema(e.getValue(), depth + 1))
                        .collect(Collectors.joining(", "));
                    yield "object{" + props + "}" + suffix + batch + defaultValue;
                }
                yield "object" + suffix + batch + defaultValue;
            }
            case UNKNOWN -> "unknown" + suffix + batch + defaultValue;
        };
        if (!StringUtils.hasText(schema.getDescription())) {
            return summary;
        }
        String description = schema.getDescription().trim().replaceAll("\\s+", " ");
        if (description.length() > 220) {
            description = description.substring(0, 217) + "...";
        }
        return summary + " - " + description;
    }

    private MultiIntentResponse buildResponse(String query,
                                              ClassificationResponse classification,
                                              Map<Integer, String> selectedActions,
                                              Map<Integer, Map<String, Object>> filledParams) {
        List<Intent> intents = new ArrayList<>(classification.getIntents().size());
        for (int i = 0; i < classification.getIntents().size(); i++) {
            ClassificationIntent classified = classification.getIntents().get(i);
            if (classified == null) {
                continue;
            }

            IntentType type = parseIntentType(classified.getType());
            String intentName = StringUtils.hasText(classified.getIntent())
                ? classified.getIntent()
                : defaultIntentName(type);

            Intent.IntentBuilder builder = Intent.builder()
                .type(type)
                .intent(intentName)
                .confidence(classified.getConfidence())
                .requiresRetrieval(classified.getRequiresRetrieval())
                .requiresGeneration(classified.getRequiresGeneration())
                .responseProfile(classified.getResponseProfile())
                .requiresTargetResolution(classified.getRequiresTargetResolution())
                .directAnswer(StringUtils.hasText(classified.getDirectAnswer()) ? classified.getDirectAnswer() : null)
                .generationInstructions(StringUtils.hasText(classified.getGenerationInstructions()) ? classified.getGenerationInstructions() : null)
                .needsAdvancedRAG(classified.getNeedsAdvancedRAG())
                .optimizedQuery(StringUtils.hasText(classified.getOptimizedQuery()) ? classified.getOptimizedQuery() : null)
                .vectorSpace(StringUtils.hasText(classified.getVectorSpace()) ? classified.getVectorSpace() : null);

            if (type == IntentType.ACTION) {
                String selected = selectedActions != null ? selectedActions.get(i) : null;
                if (!StringUtils.hasText(selected)) {
                    // Multi-step is a fallback path; treat unresolvable "ACTION" classifications as an INFORMATION
                    // request rather than deflecting to OUT_OF_SCOPE. This avoids non-deterministic OUT_OF_SCOPE
                    // outcomes for retrieval-style queries that the model misclassified as ACTION.
                    builder.type(IntentType.INFORMATION);
                    builder.intent("information_request");
                    builder.action(null);
                    builder.actionParams(Map.of(
                        "reason",
                        "No registered action matched '" + safeActionHint(classified.getActionHint()) + "'. Treating as information request."
                    ));
                    builder.requiresRetrieval(true);
                    builder.requiresGeneration(true);
                } else {
                    builder.action(selected);
                    Map<String, Object> params = filledParams != null ? filledParams.get(i) : null;
                    if (params != null && !params.isEmpty()) {
                        builder.actionParams(params);
                    }
                }
            } else if (type == IntentType.OUT_OF_SCOPE) {
                // Same rationale: multi-step fallback should bias toward INFORMATION over OUT_OF_SCOPE so the
                // orchestration layer can attempt vector routing + RAG rather than hard deflecting.
                builder.type(IntentType.INFORMATION);
                builder.intent("information_request");
                builder.actionParams(Map.of(
                    "reason",
                    "Classified as out of scope by multi-step fallback. Treating as information request."
                ));
                builder.requiresRetrieval(true);
                builder.requiresGeneration(true);
            }

            intents.add(builder.build());
        }

        Map<String, Object> responseMetadata = new LinkedHashMap<>();
        responseMetadata.put(RESPONSE_METADATA_KEY_EXTRACTION_MODE, RESPONSE_METADATA_VALUE_MULTI_STEP);
        if (classification != null
            && classification.getMetadata() != null
            && classification.getMetadata().get(RESPONSE_METADATA_KEY_RETRIEVAL_QUERY_HINT) instanceof String hint
            && StringUtils.hasText(hint)) {
            responseMetadata.put(RESPONSE_METADATA_KEY_RETRIEVAL_QUERY_HINT, hint.trim());
        }
        return MultiIntentResponse.builder()
            .intents(intents)
            .metadata(Collections.unmodifiableMap(responseMetadata))
            .build();
    }

    private String safeActionHint(String value) {
        return value != null ? value : "";
    }

    private IntentType parseIntentType(String raw) {
        if (!StringUtils.hasText(raw)) {
            return IntentType.OUT_OF_SCOPE;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        try {
            return IntentType.valueOf(normalized);
        } catch (Exception ex) {
            return IntentType.OUT_OF_SCOPE;
        }
    }

    private String defaultIntentName(IntentType type) {
        if (type == null) {
            return "unknown";
        }
        return switch (type) {
            case ACTION -> "action_request";
            case INFORMATION -> "information_request";
            case OUT_OF_SCOPE -> "out_of_scope";
            case CONFIRMATION_POSITIVE -> "confirmation_positive";
            case CONFIRMATION_NEGATIVE -> "confirmation_negative";
        };
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ClassificationResponse {
        private List<ClassificationIntent> intents = new ArrayList<>();
        private Map<String, Object> metadata = Collections.emptyMap();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ClassificationIntent {
        private Integer index;
        private String type;
        private String intent;
        private String actionHint;
        private Double confidence;
        private Boolean requiresRetrieval;
        private Boolean requiresGeneration;
        private ResponseGenerationProfile responseProfile;
        private Boolean requiresTargetResolution;
        private String directAnswer;
        private String generationInstructions;
        private Boolean needsAdvancedRAG;
        private String optimizedQuery;
        private String vectorSpace;

        ClassificationIntent withIndex(int idx) {
            ClassificationIntent copy = new ClassificationIntent();
            copy.index = idx;
            copy.type = this.type;
            copy.intent = this.intent;
            copy.actionHint = this.actionHint;
            copy.confidence = this.confidence;
            copy.requiresRetrieval = this.requiresRetrieval;
            copy.requiresGeneration = this.requiresGeneration;
            copy.responseProfile = this.responseProfile;
            copy.requiresTargetResolution = this.requiresTargetResolution;
            copy.directAnswer = this.directAnswer;
            copy.generationInstructions = this.generationInstructions;
            copy.needsAdvancedRAG = this.needsAdvancedRAG;
            copy.optimizedQuery = this.optimizedQuery;
            copy.vectorSpace = this.vectorSpace;
            return copy;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ActionSelectionResponse {
        private List<ActionMapping> mappings = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ActionMapping {
        private Integer intentIndex;
        private String selectedAction;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ActionParamsFillResponse {
        private List<ActionParamsMapping> mappings = new ArrayList<>();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class ActionParamsMapping {
        private Integer intentIndex;
        private Map<String, Object> actionParams = new LinkedHashMap<>();
    }
}
