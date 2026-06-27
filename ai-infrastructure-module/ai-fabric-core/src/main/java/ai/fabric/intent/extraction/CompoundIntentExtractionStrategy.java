package ai.fabric.intent.extraction;

import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.EnrichedPromptBuilder;
import ai.fabric.intent.IntentExtractionJsonSupport;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Standard compound intent extraction (fast path).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompoundIntentExtractionStrategy implements IntentExtractionStrategy {

    private static final String ENTITY_TYPE = "intent_extraction";
    private static final String GENERATION_TYPE = "intent_extraction";

    private final AICoreService aiCoreService;
    private final EnrichedPromptBuilder enrichedPromptBuilder;
    private final IntentExtractionJsonSupport jsonSupport;
    private final IntentExtractionValidator validator;

    @Override
    public ExtractionAttempt attemptExtract(IntentExtractionInput input, OrchestrationContext context) {
        String query = input != null ? input.userQuery() : null;
        String currentUserMessage = input != null ? input.currentUserMessage() : null;

        String systemPrompt = enrichedPromptBuilder.buildSystemPrompt(context);
        String userPrompt = enrichedPromptBuilder.buildUserPrompt(currentUserMessage);

        AIGenerationRequest request = AIGenerationRequest.builder()
            .entityId("intent-" + UUID.randomUUID())
            .entityType(ENTITY_TYPE)
            .generationType(GENERATION_TYPE)
            .systemPrompt(systemPrompt)
            .prompt(userPrompt)
            .messages(input != null ? input.historyMessages() : List.of())
            .parameters(jsonSupport.jsonOnlyResponseParameters())
            .authContext(OrchestrationAuthContextResolver.from(context != null ? context : OrchestrationContext.anonymous()))
            .build();

        try {
            long startNanos = System.nanoTime();
            AIGenerationResponse response = aiCoreService.generateContent(request, LlmPurpose.ORCHESTRATION);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            String content = response != null ? response.getContent() : null;
            if (!StringUtils.hasText(content)) {
                return ExtractionAttempt.builder()
                    .success(false)
                    .errorMessage("Empty response from LLM")
                    .strategyName(getStrategyName())
                    .generationRequest(request)
                    .rawContent(content)
                    .llmCalls(1)
                    .processingTimeMs(elapsedMs)
                    .providerProcessingTimeMs(response != null ? response.getProcessingTimeMs() : null)
                    .model(response != null ? response.getModel() : null)
                    .build();
            }

            MultiIntentResponse parsed;
            try {
                parsed = jsonSupport.parseResponse(content);
            } catch (Exception parseException) {
                String sanitized = jsonSupport.stripCodeFences(content);
                IntentExtractionValidator.ValidationResult validationResult = new IntentExtractionValidator.ValidationResult(
                    false,
                    IntentExtractionValidator.ErrorCategory.STRUCTURAL,
                    List.of("Unable to parse intent extraction JSON: " + parseException.getMessage()),
                    List.of()
                );

                return ExtractionAttempt.builder()
                    .success(false)
                    .strategyName(getStrategyName())
                    .generationRequest(request)
                    .rawContent(sanitized)
                    .validationResult(validationResult)
                    .errorMessage(parseException.getMessage())
                    .exception(parseException)
                    .llmCalls(1)
                    .processingTimeMs(elapsedMs)
                    .providerProcessingTimeMs(response != null ? response.getProcessingTimeMs() : null)
                    .model(response != null ? response.getModel() : null)
                    .build();
            }

            String sanitized = jsonSupport.stripCodeFences(content);
            IntentExtractionValidator.ValidationResult validation = validator.validate(parsed);

            return ExtractionAttempt.builder()
                .success(validation.valid())
                .response(parsed)
                .validationResult(validation)
                .rawContent(sanitized)
                .generationRequest(request)
                .strategyName(getStrategyName())
                .llmCalls(1)
                .processingTimeMs(elapsedMs)
                .providerProcessingTimeMs(response != null ? response.getProcessingTimeMs() : null)
                .model(response != null ? response.getModel() : null)
                .build();
        } catch (Exception ex) {
            log.warn("Compound extraction failed: {}", ex.getMessage());
            return ExtractionAttempt.builder()
                .success(false)
                .errorMessage(ex.getMessage())
                .exception(ex)
                .strategyName(getStrategyName())
                .generationRequest(request)
                .llmCalls(1)
                .build();
        }
    }

    @Override
    public String getStrategyName() {
        return "compound";
    }
}
