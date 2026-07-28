package ai.fabric.intent.extraction;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.EnrichedPromptBuilder;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.CapabilityAwareActionMetadataSupport;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Completion step for contract-incomplete (but structurally valid) intent extraction outputs.
 *
 * <p>This is not structural repair; it is used to fill required action fields (action name, actionParams, etc.)
 * guided by deterministic validator signals and action metadata.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompletionIntentExtractionStrategy {

    private static final String ENTITY_TYPE = "intent_extraction";
    private static final String GENERATION_TYPE = "intent_extraction_completion";

    private static final String TEMPLATE_FAMILY = "intent-extraction/completion";
    private static final String TEMPLATE_SYSTEM_ADDON = "system-addon";
    private static final String TEMPLATE_USER = "user";

    private static final String PLACEHOLDER_ALLOWED_ACTIONS = "allowed_actions";
    private static final String PLACEHOLDER_VALIDATION_ISSUES = "validation_issues";
    private static final String PLACEHOLDER_USER_REQUEST = "user_request";
    private static final String PLACEHOLDER_PARTIAL_JSON = "partial_json";

    private final AICoreService aiCoreService;
    private final EnrichedPromptBuilder enrichedPromptBuilder;
    private final IntentExtractionJsonSupport jsonSupport;
    private final AIActionRegistry actionHandlerRegistry;
    private final IntentExtractionValidator validator;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    public ExtractionAttempt attemptComplete(IntentExtractionInput input, OrchestrationContext context, ExtractionAttempt previousAttempt) {
        String query = input != null ? input.userQuery() : null;
        String currentUserMessage = input != null ? input.currentUserMessage() : null;

        if (!StringUtils.hasText(query)) {
            return ExtractionAttempt.builder()
                .success(false)
                .strategyName(getStrategyName())
                .errorMessage("Completion requires a non-empty query")
                .llmCalls(0)
                .build();
        }
        if (previousAttempt == null || previousAttempt.getResponse() == null) {
            return ExtractionAttempt.builder()
                .success(false)
                .strategyName(getStrategyName())
                .errorMessage("Completion requires a previous attempt with a parsed response")
                .llmCalls(0)
                .build();
        }

        OrchestrationContext safeContext = context != null ? context : OrchestrationContext.anonymous();
        input.validateIdentity(safeContext);

        IntentExtractionValidator.ValidationResult priorValidation = previousAttempt.getValidationResult();
        List<IntentExtractionValidator.ValidationIssue> errorIssues = priorValidation != null && priorValidation.issues() != null
            ? priorValidation.issues().stream()
                .filter(Objects::nonNull)
                .filter(issue -> issue.severity() == IntentExtractionValidator.Severity.ERROR)
                .toList()
            : List.of();

        String systemPrompt = enrichedPromptBuilder.buildSystemPrompt(safeContext)
            + "\n\n"
            + promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM_ADDON).template();

        String allowedActions = buildAllowedActionsSpec(safeContext);
        String issuesPayload = formatIssues(errorIssues);
        String partialJson = toJson(previousAttempt.getResponse());
        String prompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_USER).template(),
            Map.of(
                PLACEHOLDER_ALLOWED_ACTIONS, allowedActions,
                PLACEHOLDER_VALIDATION_ISSUES, issuesPayload,
                PLACEHOLDER_USER_REQUEST, StringUtils.hasText(currentUserMessage) ? currentUserMessage : query,
                PLACEHOLDER_PARTIAL_JSON, partialJson
            )
        );

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("intent-" + UUID.randomUUID())
            .entityType(ENTITY_TYPE)
            .generationType(GENERATION_TYPE)
            .systemPrompt(systemPrompt)
            .prompt(prompt)
            .messages(input != null ? input.historyMessages() : List.of())
            .parameters(jsonSupport.jsonOnlyResponseParameters())
            .authContext(input.authContext(safeContext))
            .build();

        int llmCalls = 1;
        try {
            long startNanos = System.nanoTime();
            AIGenerationResponse response = aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            String content = response != null ? response.getContent() : null;
            if (!StringUtils.hasText(content)) {
                return ExtractionAttempt.builder()
                    .success(false)
                    .strategyName(getStrategyName())
                    .generationRequest(request)
                    .rawContent(content)
                    .errorMessage("Completion attempt returned an empty response from provider")
                    .llmCalls(llmCalls)
                    .processingTimeMs(elapsedMs)
                    .providerProcessingTimeMs(response != null ? response.getProcessingTimeMs() : null)
                    .model(response != null ? response.getModel() : null)
                    .build();
            }

            String sanitized = jsonSupport.stripCodeFences(content);
            MultiIntentResponse parsed = jsonSupport.parseResponse(content);
            IntentExtractionValidator.ValidationResult validation = validator.validate(parsed);

            return ExtractionAttempt.builder()
                .success(validation.valid())
                .response(parsed)
                .validationResult(validation)
                .rawContent(sanitized)
                .generationRequest(request)
                .strategyName(getStrategyName())
                .llmCalls(llmCalls)
                .processingTimeMs(elapsedMs)
                .providerProcessingTimeMs(response != null ? response.getProcessingTimeMs() : null)
                .model(response != null ? response.getModel() : null)
                .build();
        } catch (Exception ex) {
            log.warn("Completion extraction failed: {}", ex.getMessage());
            return ExtractionAttempt.builder()
                .success(false)
                .strategyName(getStrategyName())
                .errorMessage(ex.getMessage())
                .exception(ex)
                .generationRequest(request)
                .llmCalls(llmCalls)
                .build();
        }
    }

    public String getStrategyName() {
        return "completion";
    }

    private String buildAllowedActionsSpec(OrchestrationContext context) {
        if (actionHandlerRegistry == null) {
            return "- <unavailable>";
        }
        List<AIActionMetaData> actions = CapabilityAwareActionMetadataSupport.visibleActions(
            actionHandlerRegistry,
            context
        );
        if (actions == null || actions.isEmpty()) {
            return "- <none>";
        }

        return actions.stream()
            .filter(Objects::nonNull)
            .filter(meta -> StringUtils.hasText(meta.getName()))
            .map(meta -> {
                Set<String> required = meta.getRequiredParameters() != null ? meta.getRequiredParameters() : Set.of();
                String requiredText = required.isEmpty() ? "" : " required=" + required;
                Map<String, String> params = meta.getParameters() != null ? meta.getParameters() : Map.of();
                String paramsText = params.isEmpty()
                    ? ""
                    : " params=" + params.entrySet().stream()
                        .filter(e -> e.getKey() != null && e.getValue() != null)
                        .map(e -> e.getKey() + ":" + e.getValue())
                        .collect(Collectors.joining(", "));
                String desc = StringUtils.hasText(meta.getDescription()) ? " - " + meta.getDescription().trim() : "";
                return "- " + meta.getName().trim() + desc + requiredText + paramsText;
            })
            .collect(Collectors.joining("\n"));
    }

    private String formatIssues(List<IntentExtractionValidator.ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "- <none>";
        }
        return issues.stream()
            .filter(Objects::nonNull)
            .map(issue -> "- " + issue.code() + " field=" + issue.field() + " intentIndex=" + issue.intentIndex() + " message=" + issue.message())
            .collect(Collectors.joining("\n"));
    }

    private String toJson(MultiIntentResponse response) {
        if (response == null) {
            return "{}";
        }
        try {
            return jsonSupport.objectMapper().writeValueAsString(response);
        } catch (Exception ex) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("intents", List.of());
            fallback.put("metadata", Map.of("serializationError", ex.getMessage()));
            try {
                return jsonSupport.objectMapper().writeValueAsString(fallback);
            } catch (Exception ignored) {
                return "{}";
            }
        }
    }
}
