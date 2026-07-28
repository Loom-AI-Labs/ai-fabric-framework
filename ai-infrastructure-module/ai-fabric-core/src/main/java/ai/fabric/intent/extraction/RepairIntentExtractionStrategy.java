package ai.fabric.intent.extraction;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Structural repair step for malformed/invalid compound extraction outputs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RepairIntentExtractionStrategy {

    private static final String TEMPLATE_FAMILY = "intent-extraction/repair";
    private static final String TEMPLATE_SYSTEM_ADDON = "system-addon";
    private static final String TEMPLATE_USER = "user";

    private static final String PLACEHOLDER_USER_REQUEST = "user_request";
    private static final String PLACEHOLDER_MALFORMED_RESPONSE = "malformed_response";

    private final AICoreService aiCoreService;
    private final IntentExtractionJsonSupport jsonSupport;
    private final IntentExtractionValidator validator;
    private final PromptTemplateResolver promptTemplateResolver;
    private final PromptRenderer promptRenderer;

    public ExtractionAttempt attemptRepair(IntentExtractionInput input, OrchestrationContext context, ExtractionAttempt previousAttempt) {
        if (previousAttempt == null || previousAttempt.getGenerationRequest() == null || !StringUtils.hasText(previousAttempt.getRawContent())) {
            return ExtractionAttempt.builder()
                .success(false)
                .strategyName(getStrategyName())
                .errorMessage("Repair requires the original generation request and raw content")
                .llmCalls(0)
                .build();
        }

        AIGenerationRequest originalRequest = previousAttempt.getGenerationRequest();
        String originalSystemPrompt = originalRequest.getSystemPrompt();
        String repairSystemPrompt = (StringUtils.hasText(originalSystemPrompt) ? originalSystemPrompt.trim() + "\n\n" : "")
            + promptRenderer.render(promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_SYSTEM_ADDON).template(), Map.of());

        String safeQuery = input != null && StringUtils.hasText(input.currentUserMessage())
            ? input.currentUserMessage()
            : (input != null && StringUtils.hasText(input.userQuery()) ? input.userQuery() : "");
        String repairPrompt = promptRenderer.render(
            promptTemplateResolver.resolve(TEMPLATE_FAMILY, TEMPLATE_USER).template(),
            Map.of(
                PLACEHOLDER_USER_REQUEST, safeQuery,
                PLACEHOLDER_MALFORMED_RESPONSE, previousAttempt.getRawContent()
            )
        );

        AIGenerationRequest repairRequest = AIGenerationRequest.builder()
            .entityId(originalRequest.getEntityId() + "-repair")
            .entityType(originalRequest.getEntityType())
            .generationType("intent_extraction_repair")
            .systemPrompt(repairSystemPrompt)
            .prompt(repairPrompt)
            .messages(originalRequest.getMessages() != null ? originalRequest.getMessages() : List.of())
            .parameters(jsonSupport.jsonOnlyResponseParameters())
            .authContext(originalRequest.getAuthContext())
            .build();

        try {
            long startNanos = System.nanoTime();
            AIGenerationResponse repairResponse = aiCoreService.generateContent(repairRequest, LlmPurpose.ORCHESTRATION);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            String repairedContent = repairResponse != null ? repairResponse.getContent() : null;
            if (!StringUtils.hasText(repairedContent)) {
                return ExtractionAttempt.builder()
                    .success(false)
                    .strategyName(getStrategyName())
                    .generationRequest(repairRequest)
                    .rawContent(repairedContent)
                    .errorMessage("Intent extraction repair attempt returned an empty response from provider")
                    .llmCalls(1)
                    .processingTimeMs(elapsedMs)
                    .providerProcessingTimeMs(repairResponse != null ? repairResponse.getProcessingTimeMs() : null)
                    .model(repairResponse != null ? repairResponse.getModel() : null)
                    .build();
            }

            String sanitized = jsonSupport.stripCodeFences(repairedContent);
            MultiIntentResponse parsed = jsonSupport.parseResponse(repairedContent);
            IntentExtractionValidator.ValidationResult validation = validator.validate(parsed);

            return ExtractionAttempt.builder()
                .success(validation.valid())
                .response(parsed)
                .validationResult(validation)
                .rawContent(sanitized)
                .generationRequest(repairRequest)
                .strategyName(getStrategyName())
                .llmCalls(1)
                .processingTimeMs(elapsedMs)
                .providerProcessingTimeMs(repairResponse != null ? repairResponse.getProcessingTimeMs() : null)
                .model(repairResponse != null ? repairResponse.getModel() : null)
                .build();
        } catch (Exception ex) {
            String diagnostic = IntentExtractionFailureSanitizer.diagnosticMessage(ex);
            log.warn("Repair extraction failed: {}", diagnostic);
            return ExtractionAttempt.builder()
                .success(false)
                .strategyName(getStrategyName())
                .errorMessage(diagnostic)
                .exception(ex)
                .generationRequest(repairRequest)
                .llmCalls(1)
                .build();
        }
    }

    public String getStrategyName() {
        return "repair";
    }
}
