package ai.fabric.intent.extraction;

import ai.fabric.config.ProgressiveIntentExtractionProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentExtractionPostProcessor;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Progressive intent extraction with a bounded fallback ladder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "ai.intent-extraction.progressive",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
public class ProgressiveIntentExtractionEngine {

    private static final String METADATA_KEY_ATTEMPTS = "attempts";

    private final ProgressiveIntentExtractionProperties properties;
    private final CompoundIntentExtractionStrategy compoundStrategy;
    private final RepairIntentExtractionStrategy repairStrategy;
    private final CompletionIntentExtractionStrategy completionStrategy;
    private final MultiStepIntentExtractionStrategy multiStepStrategy;
    private final IntentExtractionPostProcessor postProcessor;
    private final IntentExtractionValidator validator;

    public record ExtractionOutput(MultiIntentResponse response, Map<String, Object> diagnostics) {}

    public ExtractionOutput extract(IntentExtractionInput input, OrchestrationContext context) {
        long startNanos = System.nanoTime();
        String userQuery = input != null ? input.userQuery() : null;
        if (!StringUtils.hasText(userQuery)) {
            return new ExtractionOutput(
                safeDefault("Blank query"),
                diagnostics("fallback", List.of(), 0, elapsedSince(startNanos), null, null)
            );
        }

        OrchestrationContext safeContext = context != null ? context : OrchestrationContext.anonymous();
        input.validateIdentity(safeContext);

        List<Map<String, Object>> attemptEvents = new ArrayList<>();
        int totalLlmCalls = 0;

        String forceMode = normalizeMode(properties != null ? properties.getForceMode() : null);
        int maxCalls = properties != null ? properties.getMaxTotalLlmCalls() : 5;

        ExtractionAttempt compoundAttempt = null;
        if (!"multi_step".equals(forceMode)) {
            ExtractionAttempt rawAttempt = compoundStrategy.attemptExtract(input, safeContext);
            totalLlmCalls += rawAttempt.getLlmCalls();
            compoundAttempt = assessAttempt(rawAttempt, userQuery);
            attemptEvents.add(toAttemptEvent(compoundAttempt));

            if (compoundAttempt.isSuccess()) {
                MultiIntentResponse finalized = compoundAttempt.getResponse();
                Map<String, Object> diagnostics = diagnostics(
                    "compound",
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(List.of(compoundAttempt)),
                    summarizeModels(List.of(compoundAttempt))
                );
                return new ExtractionOutput(finalized, diagnostics);
            }
        }

        if ("compound".equals(forceMode)) {
            MultiIntentResponse fallback = safeDefault("Forced compound mode failed");
            return new ExtractionOutput(
                fallback,
                diagnostics(
                    "fallback",
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(listOfNonNull(compoundAttempt)),
                    summarizeModels(listOfNonNull(compoundAttempt))
                )
            );
        }

        ExtractionAttempt latestAttempt = compoundAttempt;

        boolean completionEnabled = "completion".equals(forceMode)
            || properties == null
            || properties.isCompletionEnabled();
        int completionMaxAttempts = properties != null ? properties.getCompletionMaxAttempts() : 1;

        boolean repairEnabled = properties != null && properties.isRepairEnabled();
        boolean shouldAttemptRepair = (forceMode.isEmpty() || "auto".equals(forceMode) || "repair".equals(forceMode));
        if (shouldAttemptRepair
            && (repairEnabled || "repair".equals(forceMode))
            && compoundAttempt != null
            && compoundAttempt.isStructuralFailure()
            && totalLlmCalls < maxCalls) {

            int configuredAttempts = properties != null ? properties.getRepairMaxAttempts() : 1;
            int maxRepairAttempts = Math.max("repair".equals(forceMode) ? 1 : 0, configuredAttempts);
            for (int attempt = 0; attempt < maxRepairAttempts && totalLlmCalls < maxCalls; attempt++) {
                ExtractionAttempt rawRepairAttempt = repairStrategy.attemptRepair(input, safeContext, compoundAttempt);
                totalLlmCalls += rawRepairAttempt.getLlmCalls();
                ExtractionAttempt repairAttempt = assessAttempt(rawRepairAttempt, userQuery);
                attemptEvents.add(toAttemptEvent(repairAttempt));
                latestAttempt = repairAttempt;

                if (repairAttempt.isSuccess()) {
                    MultiIntentResponse finalized = repairAttempt.getResponse();
                    Map<String, Object> diagnostics = diagnostics(
                        "repair",
                        attemptEvents,
                        totalLlmCalls,
                        elapsedSince(startNanos),
                        sumProviderProcessingTime(listOfNonNull(compoundAttempt, repairAttempt)),
                        summarizeModels(listOfNonNull(compoundAttempt, repairAttempt))
                    );
                    return new ExtractionOutput(finalized, diagnostics);
                }
            }
        }

        if ("repair".equals(forceMode)) {
            MultiIntentResponse fallback = safeDefault("Forced repair mode failed");
            return new ExtractionOutput(
                fallback,
                diagnostics(
                    "fallback",
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(listOfNonNull(compoundAttempt, latestAttempt)),
                    summarizeModels(listOfNonNull(compoundAttempt, latestAttempt))
                )
            );
        }

        ExtractionAttempt attemptForCompletion = latestAttempt != null ? latestAttempt : compoundAttempt;

        boolean shouldAttemptCompletion = (forceMode.isEmpty() || "auto".equals(forceMode) || "completion".equals(forceMode));
        if (shouldAttemptCompletion
            && completionEnabled
            && attemptForCompletion != null
            && attemptForCompletion.getValidationResult() != null
            && (attemptForCompletion.getValidationResult().errorCategory() == IntentExtractionValidator.ErrorCategory.INCOMPLETE
                || attemptForCompletion.getValidationResult().errorCategory() == IntentExtractionValidator.ErrorCategory.UNSAFE)
            && totalLlmCalls < maxCalls) {

            int maxAttempts = Math.max("completion".equals(forceMode) ? 1 : 0, completionMaxAttempts);
            ExtractionAttempt current = attemptForCompletion;

            for (int attempt = 0; attempt < maxAttempts && totalLlmCalls < maxCalls; attempt++) {
                ExtractionAttempt rawCompletionAttempt = completionStrategy.attemptComplete(input, safeContext, current);
                totalLlmCalls += rawCompletionAttempt.getLlmCalls();
                ExtractionAttempt completed = assessAttempt(rawCompletionAttempt, userQuery);
                attemptEvents.add(toAttemptEvent(completed));

                if (completed.isSuccess()) {
                    MultiIntentResponse finalized = completed.getResponse();
                    Map<String, Object> diagnostics = diagnostics(
                        "completion",
                        attemptEvents,
                        totalLlmCalls,
                        elapsedSince(startNanos),
                        sumProviderProcessingTime(listOfNonNull(compoundAttempt, latestAttempt, completed)),
                        summarizeModels(listOfNonNull(compoundAttempt, latestAttempt, completed))
                    );
                    return new ExtractionOutput(finalized, diagnostics);
                }

                current = completed;
                if (current.getValidationResult() == null
                    || (current.getValidationResult().errorCategory() != IntentExtractionValidator.ErrorCategory.INCOMPLETE
                        && current.getValidationResult().errorCategory() != IntentExtractionValidator.ErrorCategory.UNSAFE)) {
                    break;
                }
            }
        }

        if ("completion".equals(forceMode)) {
            MultiIntentResponse fallback = safeDefault("Forced completion mode failed");
            return new ExtractionOutput(
                fallback,
                diagnostics(
                    "fallback",
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(listOfNonNull(compoundAttempt, latestAttempt)),
                    summarizeModels(listOfNonNull(compoundAttempt, latestAttempt))
                )
            );
        }

        boolean multiStepEnabled = "multi_step".equals(forceMode) || properties == null || properties.isMultiStepEnabled();
        int remainingCalls = maxCalls - totalLlmCalls;
        // Multi-step extraction can use up to 3 LLM calls: classify + select actions + fill action params.
        if (multiStepEnabled && remainingCalls >= 3) {
            ExtractionAttempt rawMultiStepAttempt = multiStepStrategy.attemptExtract(input, safeContext);
            totalLlmCalls += rawMultiStepAttempt.getLlmCalls();
            ExtractionAttempt multiStepAttempt = assessAttempt(rawMultiStepAttempt, userQuery);
            attemptEvents.add(toAttemptEvent(multiStepAttempt));

            if (multiStepAttempt.isSuccess()) {
                MultiIntentResponse finalized = multiStepAttempt.getResponse();
                Map<String, Object> diagnostics = diagnostics(
                    "multi_step",
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(listOfNonNull(compoundAttempt, latestAttempt, multiStepAttempt)),
                    summarizeModels(listOfNonNull(compoundAttempt, latestAttempt, multiStepAttempt))
                );
                return new ExtractionOutput(finalized, diagnostics);
            }
        }

        MultiIntentResponse fallback = safeDefault("Intent extraction failed after bounded attempts");
        return new ExtractionOutput(
            fallback,
            diagnostics(
                "fallback",
                attemptEvents,
                totalLlmCalls,
                elapsedSince(startNanos),
                sumProviderProcessingTime(listOfNonNull(compoundAttempt, latestAttempt)),
                summarizeModels(listOfNonNull(compoundAttempt, latestAttempt))
            )
        );
    }

    private ExtractionAttempt assessAttempt(ExtractionAttempt attempt, String originalQuery) {
        if (attempt == null) {
            return null;
        }

        MultiIntentResponse response = attempt.getResponse();
        if (response == null) {
            return attempt;
        }

        MultiIntentResponse processed;
        try {
            processed = postProcessor.postProcess(response, originalQuery);
        } catch (Exception ex) {
            log.warn("Post-processing failed for strategy '{}': {}", attempt.getStrategyName(), ex.getMessage());
            IntentExtractionValidator.ValidationResult validation = new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.OTHER,
                List.of("Post-processing failed: " + ex.getMessage()),
                List.of()
            );
            return ExtractionAttempt.builder()
                .success(false)
                .response(null)
                .validationResult(validation)
                .rawContent(attempt.getRawContent())
                .generationRequest(attempt.getGenerationRequest())
                .errorMessage(attempt.getErrorMessage())
                .exception(attempt.getException())
                .strategyName(attempt.getStrategyName())
                .llmCalls(attempt.getLlmCalls())
                .processingTimeMs(attempt.getProcessingTimeMs())
                .providerProcessingTimeMs(attempt.getProviderProcessingTimeMs())
                .model(attempt.getModel())
                .build();
        }

        if (processed == null || !processed.hasIntents()) {
            IntentExtractionValidator.ValidationResult validation = new IntentExtractionValidator.ValidationResult(
                false,
                IntentExtractionValidator.ErrorCategory.STRUCTURAL,
                List.of("Extraction produced empty intents after post-processing"),
                List.of()
            );
            return ExtractionAttempt.builder()
                .success(false)
                .response(processed)
                .validationResult(validation)
                .rawContent(attempt.getRawContent())
                .generationRequest(attempt.getGenerationRequest())
                .errorMessage(attempt.getErrorMessage())
                .exception(attempt.getException())
                .strategyName(attempt.getStrategyName())
                .llmCalls(attempt.getLlmCalls())
                .processingTimeMs(attempt.getProcessingTimeMs())
                .providerProcessingTimeMs(attempt.getProviderProcessingTimeMs())
                .model(attempt.getModel())
                .build();
        }

        IntentExtractionValidator.ValidationResult validation = validator.validate(processed, originalQuery);
        return ExtractionAttempt.builder()
            .success(validation.valid())
            .response(processed)
            .validationResult(validation)
            .rawContent(attempt.getRawContent())
            .generationRequest(attempt.getGenerationRequest())
            .errorMessage(attempt.getErrorMessage())
            .exception(attempt.getException())
            .strategyName(attempt.getStrategyName())
            .llmCalls(attempt.getLlmCalls())
            .processingTimeMs(attempt.getProcessingTimeMs())
            .providerProcessingTimeMs(attempt.getProviderProcessingTimeMs())
            .model(attempt.getModel())
            .build();
    }

    private MultiIntentResponse safeDefault(String reason) {
        Intent fallbackIntent = Intent.builder()
            .type(IntentType.OUT_OF_SCOPE)
            .intent("out_of_scope")
            .confidence(0.0d)
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .actionParams(Map.of("reason", reason))
            .build();

        return MultiIntentResponse.builder()
            .intents(List.of(fallbackIntent))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .metadata(Map.of("fallback", true))
            .build();
    }

    private String normalizeMode(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> diagnostics(String path,
                                            List<Map<String, Object>> attempts,
                                            int llmCalls,
                                            Long processingTimeMs,
                                            Long providerProcessingTimeMs,
                                            String model) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("extractionPath", path);
        out.put("extractionAttempts", attempts != null ? attempts.size() : 0);
        out.put("llmCalls", llmCalls);
        if (processingTimeMs != null) {
            out.put("processingTimeMs", processingTimeMs);
        }
        if (providerProcessingTimeMs != null) {
            out.put("providerProcessingTimeMs", providerProcessingTimeMs);
        }
        if (StringUtils.hasText(model)) {
            out.put("model", model);
        }
        if (attempts != null && !attempts.isEmpty()) {
            out.put(METADATA_KEY_ATTEMPTS, Collections.unmodifiableList(attempts));
        }
        return Collections.unmodifiableMap(out);
    }

    private Map<String, Object> toAttemptEvent(ExtractionAttempt attempt) {
        Map<String, Object> event = new LinkedHashMap<>();
        if (attempt == null) {
            event.put("strategy", null);
            event.put("success", false);
            event.put("llmCalls", 0);
            return Collections.unmodifiableMap(event);
        }
        event.put("strategy", attempt.getStrategyName());
        event.put("success", attempt.isSuccess());
        event.put("llmCalls", attempt.getLlmCalls());
        if (attempt.getProcessingTimeMs() != null) {
            event.put("processingTimeMs", attempt.getProcessingTimeMs());
        }
        if (attempt.getProviderProcessingTimeMs() != null) {
            event.put("providerProcessingTimeMs", attempt.getProviderProcessingTimeMs());
        }
        if (StringUtils.hasText(attempt.getModel())) {
            event.put("model", attempt.getModel());
        }
        if (attempt.getValidationResult() != null) {
            IntentExtractionValidator.ValidationResult validation = attempt.getValidationResult();
            event.put("errorCategory", validation.errorCategory() != null ? validation.errorCategory().name() : null);
            if (validation.errors() != null && !validation.errors().isEmpty()) {
                event.put("errors", List.copyOf(validation.errors()));
            }
            if (validation.warnings() != null && !validation.warnings().isEmpty()) {
                event.put("warnings", List.copyOf(validation.warnings()));
            }
            if (validation.issues() != null && !validation.issues().isEmpty()) {
                List<String> issueCodes = validation.issues().stream()
                    .filter(Objects::nonNull)
                    .map(IntentExtractionValidator.ValidationIssue::code)
                    .filter(Objects::nonNull)
                    .map(Enum::name)
                    .distinct()
                    .toList();
                if (!issueCodes.isEmpty()) {
                    event.put("issueCodes", issueCodes);
                }
            }
        }
        if (attempt.getErrorMessage() != null) {
            event.put("errorMessage", attempt.getErrorMessage());
        }
        if (attempt.getResponse() != null && attempt.getResponse().getMetadata() != null) {
            Object normalization = attempt.getResponse().getMetadata().get("normalization");
            if (normalization != null) {
                event.put("normalization", normalization);
            }
        }
        return Collections.unmodifiableMap(event);
    }

    private Long sumProviderProcessingTime(List<ExtractionAttempt> attempts) {
        long total = 0L;
        boolean found = false;
        if (attempts != null) {
            for (ExtractionAttempt attempt : attempts) {
                if (attempt != null && attempt.getProviderProcessingTimeMs() != null) {
                    total += attempt.getProviderProcessingTimeMs();
                    found = true;
                }
            }
        }
        return found ? total : null;
    }

    private String summarizeModels(List<ExtractionAttempt> attempts) {
        List<String> models = new ArrayList<>();
        if (attempts != null) {
            for (ExtractionAttempt attempt : attempts) {
                if (attempt != null && StringUtils.hasText(attempt.getModel()) && !models.contains(attempt.getModel())) {
                    models.add(attempt.getModel());
                }
            }
        }
        return models.isEmpty() ? null : String.join(", ", models);
    }

    private List<ExtractionAttempt> listOfNonNull(ExtractionAttempt... attempts) {
        List<ExtractionAttempt> values = new ArrayList<>();
        if (attempts != null) {
            for (ExtractionAttempt attempt : attempts) {
                if (attempt != null && values.stream().noneMatch(existing -> existing == attempt)) {
                    values.add(attempt);
                }
            }
        }
        return values;
    }

    private Long elapsedSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
