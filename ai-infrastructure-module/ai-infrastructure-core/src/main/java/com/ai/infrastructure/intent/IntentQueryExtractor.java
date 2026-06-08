package com.ai.infrastructure.intent;

import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.LlmPurpose;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.exception.AIServiceException;
import com.ai.infrastructure.intent.extraction.IntentExtractionInput;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.orchestration.OrchestrationAuthContextResolver;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Calls the LLM to extract structured intents from a user query.
 */
@Slf4j
@Service
public class IntentQueryExtractor {

    private static final String TEMPLATE_FAMILY_REPAIR = "intent-extraction/repair";
    private static final String TEMPLATE_SYSTEM_ADDON_REPAIR = "system-addon";
    private static final String TEMPLATE_USER_REPAIR = "user";

    private static final String PLACEHOLDER_USER_REQUEST = "user_request";
    private static final String PLACEHOLDER_MALFORMED_RESPONSE = "malformed_response";

    private final AICoreService aiCoreService;
    private final EnrichedPromptBuilder enrichedPromptBuilder;
    private final AIActionRegistry actionHandlerRegistry;
    private final ObjectMapper objectMapper;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    public IntentQueryExtractor(AICoreService aiCoreService,
                                EnrichedPromptBuilder enrichedPromptBuilder,
                                AIActionRegistry actionHandlerRegistry,
                                ObjectMapper objectMapper,
                                PromptTemplateResolver promptTemplateResolver,
                                PromptRenderer promptRenderer) {
        this.aiCoreService = aiCoreService;
        this.enrichedPromptBuilder = enrichedPromptBuilder;
        this.actionHandlerRegistry = actionHandlerRegistry;
        this.objectMapper = objectMapper.copy()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.ACCEPT_EMPTY_ARRAY_AS_NULL_OBJECT, true)
            .configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL, true)
            // Some providers (and some model outputs) may include Java-style comments or trailing commas.
            // Be tolerant here; schema validation happens after parsing.
            .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true)
            .configure(JsonReadFeature.ALLOW_TRAILING_COMMA.mappedFeature(), true);
        this.promptTemplateResolver = promptTemplateResolver;
        this.promptRenderer = promptRenderer;
    }

    public MultiIntentResponse extract(IntentExtractionInput input, OrchestrationContext context) {
        ExtractionTrace trace = extractWithTrace(input, context);
        return trace != null ? trace.response() : null;
    }

    public ExtractionTrace extractWithTrace(IntentExtractionInput input, OrchestrationContext context) {
        String userQuery = input != null ? input.userQuery() : null;
        String currentUserMessage = input != null ? input.currentUserMessage() : null;

        if (!StringUtils.hasText(userQuery)) {
            throw new AIServiceException("Query cannot be blank when extracting intents");
        }

        OrchestrationContext safeContext = context != null ? context : OrchestrationContext.anonymous();
        safeContext.validate();

        String systemPrompt = enrichedPromptBuilder.buildSystemPrompt(safeContext);

        String userPrompt = enrichedPromptBuilder.buildUserPrompt(StringUtils.hasText(currentUserMessage) ? currentUserMessage : userQuery);

        AIGenerationRequest generationRequest = AIGenerationRequest.builder()
            .entityId("intent-" + UUID.randomUUID())
            .entityType("intent_extraction")
            .generationType("intent_extraction")
            .systemPrompt(systemPrompt)
            .prompt(userPrompt)
            .messages(input != null ? input.historyMessages() : List.of())
            .parameters(jsonOnlyResponseParameters())
            .authContext(OrchestrationAuthContextResolver.from(safeContext))
            .build();

        long startNanos = System.nanoTime();
        AIGenerationResponse generationResponse = aiCoreService.generateContent(generationRequest, LlmPurpose.ORCHESTRATION);
        String content = generationResponse != null ? generationResponse.getContent() : null;
        if (!StringUtils.hasText(content)) {
            throw new AIServiceException("Intent extraction returned an empty response from provider");
        }

        String sanitized = stripCodeFences(content);
        MultiIntentResponse response;
        Long providerProcessingTimeMs = generationResponse != null ? generationResponse.getProcessingTimeMs() : null;
        String model = generationResponse != null ? generationResponse.getModel() : null;
        try {
            response = parseResponse(sanitized);
        } catch (AIServiceException parseException) {
            log.warn("Primary intent extraction parsing failed, attempting JSON repair.", parseException);
            RepairTrace repairTrace = attemptRepair(generationRequest, sanitized, parseException);
            response = repairTrace.response();
            providerProcessingTimeMs = sumNonNull(providerProcessingTimeMs, repairTrace.providerProcessingTimeMs());
            model = joinDistinctModels(model, repairTrace.model());
        }

        response.normalize();
        coerceMisclassifiedActionIntents(response);
        validateResponse(response, userQuery);
        if (!response.hasIntents()) {
            log.warn("Intent extractor returned no intents for query '{}'", userQuery);
        }
        return new ExtractionTrace(
            response,
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos),
            providerProcessingTimeMs,
            model
        );
    }

    /**
     * Provider-agnostic guardrail: some models mislabel "summarize / explain" requests as ACTION intents
     * with an unregistered action name (e.g., "summarize"). When the intent also clearly requests RAG
     * (requiresRetrieval + requiresGeneration + vectorSpace), treat it as INFORMATION instead so the
     * pipeline can satisfy it via retrieval/generation rather than failing with ACTION_NOT_FOUND.
     */
    private void coerceMisclassifiedActionIntents(MultiIntentResponse response) {
        if (response == null || response.getIntents() == null || response.getIntents().isEmpty()) {
            return;
        }

        for (Intent intent : response.getIntents()) {
            if (intent == null || intent.getType() != IntentType.ACTION) {
                continue;
            }

            String actionName = StringUtils.hasText(intent.getAction()) ? intent.getAction() : intent.getIntent();
            if (!StringUtils.hasText(actionName)) {
                continue;
            }

            // If we already have a handler, keep it actionable.
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
            }
        }
    }

    private MultiIntentResponse parseResponse(String rawJson) {
        try {
            // First, try standard JSON parsing
            JsonNode root = objectMapper.readTree(rawJson);
            if (root == null || root.isNull()) {
                throw new AIServiceException("Intent extraction returned null JSON payload");
            }
            return objectMapper.treeToValue(root, MultiIntentResponse.class);
        } catch (JsonProcessingException firstAttempt) {
            // If standard parsing fails, try to extract JSON from the text
            String extractedJson = extractJsonFromText(rawJson);
            if (extractedJson != null && !extractedJson.equals(rawJson)) {
                try {
                    JsonNode root = objectMapper.readTree(extractedJson);
                    if (root == null || root.isNull()) {
                        throw new AIServiceException("Intent extraction returned null JSON payload");
                    }
                    return objectMapper.treeToValue(root, MultiIntentResponse.class);
                } catch (JsonProcessingException secondAttempt) {
                    log.error("Failed to parse extracted JSON: {}", extractedJson, secondAttempt);
                }
            }
            
            log.error("Failed to parse intent extraction response: {}", rawJson, firstAttempt);
            throw new AIServiceException("Unable to parse intent extraction response: " + firstAttempt.getMessage(), firstAttempt);
        }
    }

    private RepairTrace attemptRepair(AIGenerationRequest originalRequest,
                                      String malformedContent,
                                      Exception rootCause) {
        String originalSystemPrompt = originalRequest != null ? originalRequest.getSystemPrompt() : null;
        String repairSystemPrompt = (StringUtils.hasText(originalSystemPrompt) ? originalSystemPrompt.trim() + "\n\n" : "")
            + promptRenderer.render(
                promptTemplateResolver.resolve(TEMPLATE_FAMILY_REPAIR, TEMPLATE_SYSTEM_ADDON_REPAIR).template(),
                Map.of()
            );

        String originalUserPrompt = originalRequest != null ? originalRequest.getPrompt() : null;
        String safeOriginalUserPrompt = originalUserPrompt != null ? originalUserPrompt : "";
        String safeMalformedContent = malformedContent != null ? malformedContent : "";
        String repairPrompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY_REPAIR, TEMPLATE_USER_REPAIR).template(),
            Map.of(
                PLACEHOLDER_USER_REQUEST, safeOriginalUserPrompt,
                PLACEHOLDER_MALFORMED_RESPONSE, safeMalformedContent
            )
        );

        AIGenerationRequest repairRequest = AIGenerationRequest.builder()
            .entityId(originalRequest.getEntityId() + "-repair")
            .entityType(originalRequest.getEntityType())
            .generationType("intent_extraction_repair")
            .systemPrompt(repairSystemPrompt)
            .prompt(repairPrompt)
            .messages(originalRequest.getMessages() != null ? originalRequest.getMessages() : List.of())
            .parameters(jsonOnlyResponseParameters())
            .authContext(originalRequest.getAuthContext())
            .build();

        try {
            AIGenerationResponse repairResponse = aiCoreService.generateContent(repairRequest, LlmPurpose.ORCHESTRATION);
            String repairedContent = repairResponse != null ? repairResponse.getContent() : null;
            if (!StringUtils.hasText(repairedContent)) {
                throw new AIServiceException("Intent extraction repair attempt returned an empty response from provider");
            }
            return new RepairTrace(
                parseResponse(stripCodeFences(repairedContent)),
                repairResponse != null ? repairResponse.getProcessingTimeMs() : null,
                repairResponse != null ? repairResponse.getModel() : null
            );
        } catch (Exception repairException) {
            repairException.addSuppressed(rootCause);
            if (repairException instanceof AIServiceException aiServiceException) {
                throw aiServiceException;
            }
            throw new AIServiceException("Unable to repair intent extraction response: " + repairException.getMessage(), repairException);
        }
    }

    private Long sumNonNull(Long first, Long second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first + second;
    }

    private String joinDistinctModels(String first, String second) {
        if (!StringUtils.hasText(first)) {
            return second;
        }
        if (!StringUtils.hasText(second) || Objects.equals(first, second)) {
            return first;
        }
        return first + ", " + second;
    }

    public record ExtractionTrace(
        MultiIntentResponse response,
        Long processingTimeMs,
        Long providerProcessingTimeMs,
        String model
    ) {}

    private record RepairTrace(
        MultiIntentResponse response,
        Long providerProcessingTimeMs,
        String model
    ) {}

    private String extractJsonFromText(String text) {
        // Try to find JSON object {...} in the text
        int startIdx = text.indexOf('{');
        if (startIdx >= 0) {
            int endIdx = text.lastIndexOf('}');
            if (endIdx > startIdx) {
                return text.substring(startIdx, endIdx + 1);
            }
        }
        return text;
    }

    private Map<String, Object> jsonOnlyResponseParameters() {
        // Provider-agnostic hint: OpenAI/Azure support `response_format`, Gemini maps it to responseMimeType,
        // and other providers may ignore it while still benefiting from the JSON-only system prompt contract.
        return Map.of(
            "response_format", Map.of("type", "json_object")
        );
    }

    private void validateResponse(MultiIntentResponse response, String originalQuery) {
        if (response.getIntents() == null) {
            response.setIntents(List.of());
        }

	        for (Intent intent : response.getIntents()) {
	            if (!intent.hasValidType()) {
	                throw new AIServiceException("Intent is missing a valid type attribute");
	            }
	            if (!intent.hasMeaningfulName()) {
	                // Provider-agnostic tolerance: some models emit OUT_OF_SCOPE intents without a stable name.
	                // For OUT_OF_SCOPE we do not require `intent` / `action` because execution does not depend on it.
	                if (intent.getType() == IntentType.OUT_OF_SCOPE) {
	                    intent.setIntent("out_of_scope");
	                } else {
	                    throw new AIServiceException("Intent is missing the 'intent' or 'action' field");
	                }
	            }
	            validateRelationshipActionParams(intent, originalQuery);
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

    private void validateRelationshipActionParams(Intent intent, String originalQuery) {
        if (intent.getType() != IntentType.ACTION) {
            return;
        }

        // Provider-agnostic: some models emit action="relationship query" / "relationship-query" etc.
        // Treat anything that resolves to the registered relationship_query action as relationship_query
        // for deterministic parameter normalization (especially actionParams.query).
        String actionName = StringUtils.hasText(intent.getAction()) ? intent.getAction() : intent.getIntent();
        String canonicalActionName = actionName;
        if (actionHandlerRegistry != null && StringUtils.hasText(actionName)) {
            // Be defensive: mocks can return null instead of Optional.empty().
            var metadataOpt = actionHandlerRegistry.findMetadata(actionName);
            if (metadataOpt != null) {
                canonicalActionName = metadataOpt
                    .map(com.ai.infrastructure.intent.action.AIActionMetaData::getName)
                    .orElse(actionName);
            }
        }
        if (!"relationship_query".equalsIgnoreCase(canonicalActionName)) {
            return;
        }

        Map<String, Object> params = intent.getActionParams();
        Map<String, Object> mutable = params != null ? new LinkedHashMap<>(params) : new LinkedHashMap<>();

        // The relationship_query action handler requires actionParams.query.
        // Some providers omit it. We do NOT attempt to parse/strip natural-language directives here.
        // The intent extraction model is responsible for splitting relational actionParams.query from any
        // post-action generation request (requiresGeneration + generationInstructions).
        Object rawQuery = mutable.get("query");
        if (rawQuery instanceof String text && StringUtils.hasText(text)) {
            mutable.put("query", RelationshipQueryHintPrefix.stripIfPresent(text));
        } else if (StringUtils.hasText(originalQuery)) {
            String stripped = RelationshipQueryHintPrefix.stripIfPresent(originalQuery);
            if (StringUtils.hasText(stripped)) {
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
        intent.setActionParams(mutable);
    }

    private String stripCodeFences(String content) {
        String trimmed = content.trim();
        while (trimmed.startsWith("###")) {
            int nextNewline = trimmed.indexOf('\n');
            if (nextNewline < 0) {
                break;
            }
            trimmed = trimmed.substring(nextNewline + 1).trim();
        }

        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            if (firstNewline > 0) {
                trimmed = trimmed.substring(firstNewline + 1);
            }
        }

        int firstFence = trimmed.indexOf("```");
        if (firstFence >= 0) {
            int endFence = trimmed.indexOf("```", firstFence + 3);
            if (endFence > firstFence) {
                trimmed = trimmed.substring(firstFence + 3, endFence);
            }
        }

        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }
}
