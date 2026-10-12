package ai.fabric.intent.extraction;

import ai.fabric.config.ProgressiveIntentExtractionProperties;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.exception.AIServiceException;
import ai.fabric.intent.IntentExtractionPostProcessor;
import ai.fabric.intent.IntentExtractionValidator;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.policy.SemanticRecoveryPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
    private static final String PROVIDER_FAILURE_REASON =
        "INTENT_PROVIDER_FAILED";
    private static final String EXTRACTION_FAILURE_REASON =
        "INTENT_EXTRACTION_FAILED";

    private final ProgressiveIntentExtractionProperties properties;
    private final CompoundIntentExtractionStrategy compoundStrategy;
    private final RepairIntentExtractionStrategy repairStrategy;
    private final CompletionIntentExtractionStrategy completionStrategy;
    private final MultiStepIntentExtractionStrategy multiStepStrategy;
    private final IntentExtractionPostProcessor postProcessor;
    private final IntentExtractionValidator validator;

    public record ExtractionOutput(
        MultiIntentResponse response,
        Map<String, Object> diagnostics,
        ExtractionFailure failure
    ) {
        public ExtractionOutput(
            MultiIntentResponse response,
            Map<String, Object> diagnostics
        ) {
            this(response, diagnostics, null);
        }
    }

    /**
     * Safe failure details for callers that must not silently accept a fallback
     * intent after the bounded extraction ladder is exhausted.
     */
    public record ExtractionFailure(
        String reason,
        String publicMessage,
        boolean retryable,
        List<IntentExtractionValidator.IssueCode> issueCodes
    ) {
        public ExtractionFailure {
            reason = requireText(reason, "reason");
            publicMessage = requireText(publicMessage, "publicMessage");
            issueCodes = issueCodes == null
                ? List.of()
                : List.copyOf(new LinkedHashSet<>(issueCodes));
        }

        public ExtractionFailure(
            String reason,
            String publicMessage,
            boolean retryable
        ) {
            this(reason, publicMessage, retryable, List.of());
        }

        private static String requireText(String value, String field) {
            String normalized = Objects.requireNonNull(
                value,
                field + " is required"
            ).trim();
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(field + " is required");
            }
            return normalized;
        }
    }

    public ExtractionOutput extract(IntentExtractionInput input, OrchestrationContext context) {
        return extract(input, context, SemanticRecoveryPolicy.strictDefaults());
    }

    public ExtractionOutput extract(IntentExtractionInput input,
                                    OrchestrationContext context,
                                    SemanticRecoveryPolicy semanticRecoveryPolicy) {
        long startNanos = System.nanoTime();
        SemanticRecoveryPolicy recoveryPolicy = semanticRecoveryPolicy != null
            ? semanticRecoveryPolicy
            : SemanticRecoveryPolicy.strictDefaults();
        String userQuery = input != null ? input.userQuery() : null;
        if (!StringUtils.hasText(userQuery)) {
            return terminalOutput(
                List.of(),
                List.of(),
                0,
                startNanos,
                recoveryPolicy
            );
        }

        OrchestrationContext safeContext = context != null ? context : OrchestrationContext.anonymous();
        input.validateIdentity(safeContext);

        List<Map<String, Object>> attemptEvents = new ArrayList<>();
        List<ExtractionAttempt> attempts = new ArrayList<>();
        int totalLlmCalls = 0;

        String forceMode = normalizeMode(properties != null ? properties.getForceMode() : null);
        int maxCalls = properties != null ? properties.getMaxTotalLlmCalls() : 5;

        ExtractionAttempt compoundAttempt = null;
        if (!"multi_step".equals(forceMode)) {
            ExtractionAttempt rawAttempt = compoundStrategy.attemptExtract(input, safeContext);
            totalLlmCalls += rawAttempt.getLlmCalls();
            compoundAttempt = assessAttempt(rawAttempt, userQuery);
            attemptEvents.add(toAttemptEvent(compoundAttempt));
            attempts.add(compoundAttempt);

            if (compoundAttempt.isSuccess()) {
                MultiIntentResponse finalized = compoundAttempt.getResponse();
                Map<String, Object> diagnostics = diagnostics(
                    "compound",
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(List.of(compoundAttempt)),
                    summarizeModels(List.of(compoundAttempt)),
                    recoveryPolicy
                );
                return new ExtractionOutput(finalized, diagnostics);
            }
        }

        if ("compound".equals(forceMode)) {
            return terminalOutput(
                attempts,
                attemptEvents,
                totalLlmCalls,
                startNanos,
                recoveryPolicy
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
                ExtractionAttempt repairAttempt = enforceSemanticRecoveryPolicy(
                    assessAttempt(rawRepairAttempt, userQuery),
                    attempts,
                    recoveryPolicy,
                    userQuery
                );
                attemptEvents.add(toAttemptEvent(repairAttempt));
                attempts.add(repairAttempt);
                latestAttempt = repairAttempt;

                if (repairAttempt.isSuccess()) {
                    MultiIntentResponse finalized = repairAttempt.getResponse();
                    Map<String, Object> diagnostics = diagnostics(
                        "repair",
                        attemptEvents,
                        totalLlmCalls,
                        elapsedSince(startNanos),
                        sumProviderProcessingTime(listOfNonNull(compoundAttempt, repairAttempt)),
                        summarizeModels(listOfNonNull(compoundAttempt, repairAttempt)),
                        recoveryPolicy
                    );
                    return new ExtractionOutput(finalized, diagnostics);
                }
            }
        }

        if ("repair".equals(forceMode)) {
            return terminalOutput(
                attempts,
                attemptEvents,
                totalLlmCalls,
                startNanos,
                recoveryPolicy
            );
        }

        ExtractionAttempt attemptForCompletion = latestAttempt != null ? latestAttempt : compoundAttempt;

        boolean shouldAttemptCompletion = (forceMode.isEmpty() || "auto".equals(forceMode) || "completion".equals(forceMode));
        if (shouldAttemptCompletion
            && completionEnabled
            && attemptForCompletion != null
            && !SemanticRecoveryPromptSupport.requiresSemanticReplan(attemptForCompletion)
            && attemptForCompletion.getValidationResult() != null
            && (attemptForCompletion.getValidationResult().errorCategory() == IntentExtractionValidator.ErrorCategory.INCOMPLETE
                || attemptForCompletion.getValidationResult().errorCategory() == IntentExtractionValidator.ErrorCategory.UNSAFE)
            && totalLlmCalls < maxCalls) {

            int maxAttempts = Math.max("completion".equals(forceMode) ? 1 : 0, completionMaxAttempts);
            ExtractionAttempt current = attemptForCompletion;

            for (int attempt = 0; attempt < maxAttempts && totalLlmCalls < maxCalls; attempt++) {
                ExtractionAttempt rawCompletionAttempt = completionStrategy.attemptComplete(
                    input,
                    safeContext,
                    current,
                    recoveryPolicy
                );
                totalLlmCalls += rawCompletionAttempt.getLlmCalls();
                ExtractionAttempt completed = enforceSemanticRecoveryPolicy(
                    assessAttempt(rawCompletionAttempt, userQuery),
                    attempts,
                    recoveryPolicy,
                    userQuery
                );
                attemptEvents.add(toAttemptEvent(completed));
                attempts.add(completed);

                if (completed.isSuccess()) {
                    MultiIntentResponse finalized = completed.getResponse();
                    Map<String, Object> diagnostics = diagnostics(
                        "completion",
                        attemptEvents,
                        totalLlmCalls,
                        elapsedSince(startNanos),
                        sumProviderProcessingTime(listOfNonNull(compoundAttempt, latestAttempt, completed)),
                        summarizeModels(listOfNonNull(compoundAttempt, latestAttempt, completed)),
                        recoveryPolicy
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
            return terminalOutput(
                attempts,
                attemptEvents,
                totalLlmCalls,
                startNanos,
                recoveryPolicy
            );
        }

        ExtractionAttempt semanticRecoveryOrigin = semanticRecoveryOrigin(attempts);
        if (SemanticRecoveryPromptSupport.requiresSemanticReplan(semanticRecoveryOrigin)
            && !semanticReplanPermitted(semanticRecoveryOrigin, recoveryPolicy)) {
            return terminalOutput(
                attempts,
                attemptEvents,
                totalLlmCalls,
                startNanos,
                recoveryPolicy
            );
        }

        boolean multiStepEnabled = "multi_step".equals(forceMode) || properties == null || properties.isMultiStepEnabled();
        int remainingCalls = maxCalls - totalLlmCalls;
        // Multi-step extraction can use up to 3 LLM calls: classify + select actions + fill action params.
        if (multiStepEnabled && remainingCalls >= 3) {
            ExtractionAttempt rawMultiStepAttempt = multiStepStrategy.attemptExtract(
                input,
                safeContext,
                semanticRecoveryOrigin,
                recoveryPolicy
            );
            totalLlmCalls += rawMultiStepAttempt.getLlmCalls();
            ExtractionAttempt multiStepAttempt = enforceSemanticRecoveryPolicy(
                assessAttempt(rawMultiStepAttempt, userQuery),
                attempts,
                recoveryPolicy,
                userQuery
            );
            attemptEvents.add(toAttemptEvent(multiStepAttempt));
            attempts.add(multiStepAttempt);

            if (multiStepAttempt.isSuccess()) {
                MultiIntentResponse finalized = multiStepAttempt.getResponse();
                Map<String, Object> diagnostics = diagnostics(
                    "multi_step",
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(listOfNonNull(compoundAttempt, latestAttempt, multiStepAttempt)),
                    summarizeModels(listOfNonNull(compoundAttempt, latestAttempt, multiStepAttempt)),
                    recoveryPolicy
                );
                return new ExtractionOutput(finalized, diagnostics);
            }
        }

        return terminalOutput(
            attempts,
            attemptEvents,
            totalLlmCalls,
            startNanos,
            recoveryPolicy
        );
    }

    private ExtractionOutput terminalOutput(
        List<ExtractionAttempt> attempts,
        List<Map<String, Object>> attemptEvents,
        int totalLlmCalls,
        long startNanos,
        SemanticRecoveryPolicy recoveryPolicy
    ) {
        PolicyContinuation policyContinuation = policyContinuationCandidate(
            attempts,
            recoveryPolicy
        );
        if (policyContinuation != null) {
            return new ExtractionOutput(
                policyContinuation.attempt().getResponse(),
                policyRecoveryDiagnostics(
                    attemptEvents,
                    totalLlmCalls,
                    elapsedSince(startNanos),
                    sumProviderProcessingTime(attempts),
                    summarizeModels(attempts),
                    policyContinuation
                )
            );
        }
        return new ExtractionOutput(
            null,
            diagnostics(
                "failed",
                attemptEvents,
                totalLlmCalls,
                elapsedSince(startNanos),
                sumProviderProcessingTime(attempts),
                summarizeModels(attempts),
                recoveryPolicy
            ),
            extractionFailure(attempts)
        );
    }

    private PolicyContinuation policyContinuationCandidate(
        List<ExtractionAttempt> attempts,
        SemanticRecoveryPolicy recoveryPolicy
    ) {
        if (recoveryPolicy == null
            || attempts == null
            || attempts.isEmpty()) {
            return null;
        }
        ExtractionAttempt attempt = attempts.getLast();
        if (attempt == null
            || attempt.getResponse() == null
            || attempt.getValidationResult() == null
            || attempt.getValidationResult().issues() == null) {
            return null;
        }
        List<IntentExtractionValidator.ValidationIssue> errors =
            attempt.getValidationResult().issues().stream()
                .filter(Objects::nonNull)
                .filter(issue -> issue.severity()
                    == IntentExtractionValidator.Severity.ERROR)
                .toList();
        if (errors.isEmpty()) {
            return null;
        }
        if (errors.stream().allMatch(issue -> issue.code()
            == IntentExtractionValidator.IssueCode.INFORMATION_MISSING_VECTOR_SPACE)) {
            return recoveryPolicy.missingVectorSpace()
                    == OrchestrationProperties.MissingVectorSpaceRecovery.BOUNDED_ALLOWED_FAN_OUT
                ? new PolicyContinuation(
                    attempt,
                    "VECTOR_SPACE_SELECTION",
                    recoveryPolicy.missingVectorSpace().name(),
                    "DEFER_TO_BOUNDED_ALLOWED_FAN_OUT"
                )
                : null;
        }
        if (errors.stream().allMatch(issue -> issue.code()
            == IntentExtractionValidator.IssueCode.INFORMATION_INVALID_VECTOR_SPACE)) {
            return new PolicyContinuation(
                attempt,
                "VECTOR_SPACE_SELECTION",
                recoveryPolicy.invalidVectorSpace().name(),
                "DEFER_TO_VECTOR_SPACE_POLICY"
            );
        }
        if (errors.stream().allMatch(issue -> issue.code()
            == IntentExtractionValidator.IssueCode.ACTION_GROUNDING_VECTOR_SPACE_MISMATCH)) {
            return new PolicyContinuation(
                attempt,
                "ACTION_EVIDENCE_DOMAIN",
                recoveryPolicy.actionDomainMismatch().name(),
                "DEFER_TO_ACTION_DOMAIN_POLICY"
            );
        }
        return null;
    }

    private Map<String, Object> policyRecoveryDiagnostics(
        List<Map<String, Object>> attempts,
        int llmCalls,
        Long processingTimeMs,
        Long providerProcessingTimeMs,
        String model,
        PolicyContinuation policyContinuation
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("extractionPath", "policy_recovery");
        diagnostics.put("extractionAttempts", attempts != null ? attempts.size() : 0);
        diagnostics.put("llmCalls", llmCalls);
        if (processingTimeMs != null) {
            diagnostics.put("processingTimeMs", processingTimeMs);
        }
        if (providerProcessingTimeMs != null) {
            diagnostics.put("providerProcessingTimeMs", providerProcessingTimeMs);
        }
        if (StringUtils.hasText(model)) {
            diagnostics.put("model", model);
        }
        if (attempts != null && !attempts.isEmpty()) {
            diagnostics.put(METADATA_KEY_ATTEMPTS, Collections.unmodifiableList(attempts));
        }
        Map<String, Object> semanticDecision = new LinkedHashMap<>();
        semanticDecision.put("decision", policyContinuation.decision());
        semanticDecision.put("originalSource", "MODEL");
        semanticDecision.put(
            "originalValue",
            attemptDecisionSummary(attempts != null && !attempts.isEmpty()
                ? attempts.getLast()
                : Map.of())
        );
        semanticDecision.put("validationStatus", "REJECTED_BY_VALIDATION");
        semanticDecision.put("recoveryPolicy", policyContinuation.policy());
        semanticDecision.put("recoveryAttempted", attempts != null && attempts.size() > 1);
        semanticDecision.put("effectiveSource", "RECOVERY_POLICY");
        semanticDecision.put("effectiveValue", policyContinuation.effectiveValue());
        semanticDecision.put("modelCalls", llmCalls);
        semanticDecision.put("outcome", "DEFERRED_TO_GOVERNED_PIPELINE_STEP");
        diagnostics.put("semanticDecision", Collections.unmodifiableMap(semanticDecision));
        return Collections.unmodifiableMap(diagnostics);
    }

    private record PolicyContinuation(
        ExtractionAttempt attempt,
        String decision,
        String policy,
        String effectiveValue
    ) {
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

    private ExtractionAttempt enforceSemanticRecoveryPolicy(
        ExtractionAttempt candidate,
        List<ExtractionAttempt> priorAttempts,
        SemanticRecoveryPolicy policy,
        String originalQuery
    ) {
        if (candidate == null || !candidate.isSuccess() || candidate.getResponse() == null) {
            return candidate;
        }

        ExtractionAttempt origin = semanticRecoveryOrigin(priorAttempts);
        if (origin == null
            || origin.getResponse() == null
            || origin.getResponse().getIntents() == null
            || origin.getValidationResult() == null
            || origin.getValidationResult().issues() == null) {
            return candidate;
        }

        SemanticRecoveryPolicy effectivePolicy = policy != null
            ? policy
            : SemanticRecoveryPolicy.strictDefaults();
        List<Intent> originalIntents = origin.getResponse().getIntents();
        List<Intent> candidateIntents = candidate.getResponse().getIntents() != null
            ? new ArrayList<>(candidate.getResponse().getIntents())
            : new ArrayList<>();
        List<IntentExtractionValidator.ValidationIssue> sourceIssues =
            origin.getValidationResult().issues().stream()
                .filter(Objects::nonNull)
                .filter(issue -> issue.severity()
                    == IntentExtractionValidator.Severity.ERROR)
                .toList();
        List<Map<String, Object>> transitions = new ArrayList<>();

        for (int originalIndex = 0; originalIndex < originalIntents.size(); originalIndex++) {
            Intent originalIntent = originalIntents.get(originalIndex);
            if (originalIntent == null) {
                continue;
            }
            List<IntentExtractionValidator.ValidationIssue> intentIssues =
                issuesForIntent(sourceIssues, originalIndex);
            Intent effectiveIntent = correspondingIntent(
                originalIntent,
                originalIndex,
                candidateIntents
            );
            if (effectiveIntent == null) {
                String reason = "Model recovery removed or renamed a prior obligation";
                transitions.add(recoveryTransition(
                    originalIndex,
                    originalIntent,
                    null,
                    "OBLIGATION_PRESERVATION",
                    "REJECTED",
                    reason
                ));
                return rejectedSemanticRecovery(candidate, originalIndex, reason, transitions);
            }

            boolean semanticIssue = intentIssues.stream()
                .anyMatch(issue -> isActionSemanticRecoveryIssue(issue.code()));
            if (!semanticIssue) {
                String preservationFailure = siblingPreservationFailure(
                    originalIntent,
                    effectiveIntent,
                    intentIssues
                );
                if (preservationFailure != null) {
                    int candidateIndex = correspondingIntentIndex(
                        originalIntent,
                        originalIndex,
                        candidateIntents
                    );
                    if (candidateIndex < 0) {
                        transitions.add(recoveryTransition(
                            originalIndex,
                            originalIntent,
                            effectiveIntent,
                            "VALID_SIBLING_PRESERVATION",
                            "REJECTED",
                            preservationFailure
                        ));
                        return rejectedSemanticRecovery(
                            candidate,
                            originalIndex,
                            preservationFailure,
                            transitions
                        );
                    }
                    Intent preservedIntent = preserveValidatedSibling(
                        originalIntent,
                        effectiveIntent
                    );
                    candidateIntents.set(candidateIndex, preservedIntent);
                    transitions.add(siblingPreservationTransition(
                        originalIndex,
                        originalIntent,
                        effectiveIntent,
                        preservedIntent,
                        preservationFailure
                    ));
                }
                continue;
            }

            for (IntentExtractionValidator.ValidationIssue issue : intentIssues) {
                if (!isActionSemanticRecoveryIssue(issue.code())) {
                    continue;
                }
                String policyName;
                String outcome;
                String rejection = null;
                if (issue.code()
                    == IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED) {
                    policyName = effectivePolicy.unknownAction().name();
                    if (effectiveIntent.getType() == IntentType.INFORMATION) {
                        if (effectivePolicy.unknownAction()
                            != OrchestrationProperties.UnknownActionRecovery.LLM_REPLAN_TO_INFORMATION) {
                            rejection = "Unknown-action replan to information is disabled by semantic recovery policy";
                        }
                        outcome = "MODEL_REPLAN_TO_INFORMATION";
                    } else if (effectiveIntent.getType() == IntentType.ACTION) {
                        outcome = "MODEL_ACTION_REPAIR";
                    } else {
                        rejection = "Unknown-action recovery produced an unsupported intent-type transition";
                        outcome = "UNSUPPORTED_TRANSITION";
                    }
                } else {
                    policyName = effectivePolicy.actionDomainMismatch().name();
                    boolean replanAllowed = effectivePolicy.actionDomainMismatch()
                        == OrchestrationProperties.ActionDomainMismatchRecovery.DENY_AND_LLM_REPLAN;
                    if (!replanAllowed) {
                        rejection = "Action-domain model replan is disabled by semantic recovery policy";
                        outcome = "POLICY_REPLAN_DISABLED";
                    } else if (effectiveIntent.getType() == IntentType.INFORMATION) {
                        outcome = "MODEL_REPLAN_TO_INFORMATION";
                    } else if (effectiveIntent.getType() == IntentType.ACTION) {
                        outcome = "MODEL_ACTION_DOMAIN_REPAIR";
                    } else {
                        rejection = "Action-domain recovery produced an unsupported intent-type transition";
                        outcome = "UNSUPPORTED_TRANSITION";
                    }
                }

                transitions.add(recoveryTransition(
                    originalIndex,
                    originalIntent,
                    effectiveIntent,
                    issue.code().name(),
                    rejection == null ? outcome : "REJECTED",
                    policyName
                ));
                if (rejection != null) {
                    return rejectedSemanticRecovery(
                        candidate,
                        originalIndex,
                        rejection,
                        transitions
                    );
                }
            }
        }

        if (candidateIntents.equals(candidate.getResponse().getIntents())) {
            candidate.setSemanticRecoveryTransitions(List.copyOf(transitions));
            return candidate;
        }

        MultiIntentResponse effectiveResponse = MultiIntentResponse.builder()
            .intents(candidateIntents)
            .orchestrationStrategy(candidate.getResponse().getOrchestrationStrategy())
            .metadata(candidate.getResponse().getMetadata())
            .build();
        effectiveResponse.normalize();
        IntentExtractionValidator.ValidationResult effectiveValidation =
            validator.validate(effectiveResponse, originalQuery);
        return ExtractionAttempt.builder()
            .success(effectiveValidation.valid())
            .response(effectiveResponse)
            .validationResult(effectiveValidation)
            .rawContent(candidate.getRawContent())
            .generationRequest(candidate.getGenerationRequest())
            .errorMessage(effectiveValidation.valid()
                ? candidate.getErrorMessage()
                : "Server-preserved semantic recovery result did not validate")
            .exception(candidate.getException())
            .strategyName(candidate.getStrategyName())
            .llmCalls(candidate.getLlmCalls())
            .processingTimeMs(candidate.getProcessingTimeMs())
            .providerProcessingTimeMs(candidate.getProviderProcessingTimeMs())
            .model(candidate.getModel())
            .semanticRecoveryTransitions(List.copyOf(transitions))
            .build();
    }

    private boolean semanticReplanPermitted(ExtractionAttempt origin,
                                            SemanticRecoveryPolicy policy) {
        List<IntentExtractionValidator.ValidationIssue> issues =
            origin != null && origin.getValidationResult() != null
                ? origin.getValidationResult().issues()
                : List.of();
        SemanticRecoveryPolicy effectivePolicy = policy != null
            ? policy
            : SemanticRecoveryPolicy.strictDefaults();
        return issues != null && issues.stream()
            .filter(Objects::nonNull)
            .filter(issue -> issue.severity()
                == IntentExtractionValidator.Severity.ERROR)
            .filter(issue -> isActionSemanticRecoveryIssue(issue.code()))
            .allMatch(issue -> issue.code()
                    == IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED
                || effectivePolicy.actionDomainMismatch()
                    == OrchestrationProperties.ActionDomainMismatchRecovery.DENY_AND_LLM_REPLAN);
    }

    private ExtractionAttempt semanticRecoveryOrigin(List<ExtractionAttempt> attempts) {
        if (attempts == null || attempts.isEmpty()) {
            return null;
        }
        for (int index = attempts.size() - 1; index >= 0; index--) {
            ExtractionAttempt attempt = attempts.get(index);
            if (SemanticRecoveryPromptSupport.requiresSemanticReplan(attempt)) {
                return attempt;
            }
        }
        for (int index = attempts.size() - 1; index >= 0; index--) {
            ExtractionAttempt attempt = attempts.get(index);
            if (attempt == null
                || attempt.getResponse() == null
                || !attempt.getResponse().hasIntents()
                || attempt.getValidationResult() == null
                || attempt.getValidationResult().errorCategory()
                    == IntentExtractionValidator.ErrorCategory.STRUCTURAL
                || attempt.getValidationResult().issues() == null) {
                continue;
            }
            boolean hasError = attempt.getValidationResult().issues().stream()
                .filter(Objects::nonNull)
                .anyMatch(issue -> issue.severity()
                    == IntentExtractionValidator.Severity.ERROR);
            if (hasError) {
                return attempt;
            }
        }
        return null;
    }

    private List<IntentExtractionValidator.ValidationIssue> issuesForIntent(
        List<IntentExtractionValidator.ValidationIssue> issues,
        int intentIndex
    ) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        return issues.stream()
            .filter(Objects::nonNull)
            .filter(issue -> issue.intentIndex() == intentIndex)
            .toList();
    }

    private boolean isActionSemanticRecoveryIssue(
        IntentExtractionValidator.IssueCode code
    ) {
        return code == IntentExtractionValidator.IssueCode.ACTION_UNREGISTERED
            || code == IntentExtractionValidator.IssueCode.ACTION_GROUNDING_VECTOR_SPACE_MISMATCH;
    }

    private Intent correspondingIntent(Intent original,
                                       int originalIndex,
                                       List<Intent> candidates) {
        int index = correspondingIntentIndex(original, originalIndex, candidates);
        return index >= 0 ? candidates.get(index) : null;
    }

    private int correspondingIntentIndex(Intent original,
                                         int originalIndex,
                                         List<Intent> candidates) {
        if (original == null || candidates == null || candidates.isEmpty()) {
            return -1;
        }
        if (StringUtils.hasText(original.getObligationId())) {
            String obligationId = original.getObligationId().trim();
            for (int index = 0; index < candidates.size(); index++) {
                Intent candidate = candidates.get(index);
                if (candidate != null
                    && StringUtils.hasText(candidate.getObligationId())
                    && obligationId.equals(candidate.getObligationId().trim())) {
                    return index;
                }
            }
            return -1;
        }
        return originalIndex >= 0 && originalIndex < candidates.size()
            ? originalIndex
            : -1;
    }

    private Intent preserveValidatedSibling(Intent original,
                                            Intent modelProposal) {
        String obligationId = StringUtils.hasText(original.getObligationId())
            ? original.getObligationId().trim()
            : modelProposal != null && StringUtils.hasText(modelProposal.getObligationId())
                ? modelProposal.getObligationId().trim()
                : null;
        return Intent.builder()
            .type(original.getType())
            .intent(original.getIntent())
            .confidence(original.getConfidence())
            .action(original.getAction())
            .actionParams(original.getActionParams())
            .vectorSpace(original.getVectorSpace())
            .requiresRetrieval(original.getRequiresRetrieval())
            .requiresGeneration(original.getRequiresGeneration())
            .responseProfile(original.getResponseProfile())
            .requiresTargetResolution(original.getRequiresTargetResolution())
            .directAnswer(original.getDirectAnswer())
            .generationInstructions(original.getGenerationInstructions())
            .needsAdvancedRAG(original.getNeedsAdvancedRAG())
            .optimizedQuery(original.getOptimizedQuery())
            .obligationId(obligationId)
            .userGoal(original.getUserGoal())
            .missingInformation(original.getMissingInformation())
            .clarificationQuestion(original.getClarificationQuestion())
            .uncertaintyReason(original.getUncertaintyReason())
            .nextStepRecommended(original.getNextStepRecommended())
            .build();
    }

    private String siblingPreservationFailure(
        Intent original,
        Intent effective,
        List<IntentExtractionValidator.ValidationIssue> originalIssues
    ) {
        if (original.getType() != effective.getType()) {
            return "Model recovery changed the type of a sibling obligation that was not rejected for action semantics";
        }
        if (original.getType() == IntentType.ACTION
            && !Objects.equals(actionId(original), actionId(effective))) {
            return "Model recovery changed the action ID of a sibling obligation that was already valid";
        }
        boolean vectorSpaceRejected = originalIssues != null && originalIssues.stream()
            .filter(Objects::nonNull)
            .map(IntentExtractionValidator.ValidationIssue::code)
            .anyMatch(code -> code
                    == IntentExtractionValidator.IssueCode.INFORMATION_MISSING_VECTOR_SPACE
                || code == IntentExtractionValidator.IssueCode.INFORMATION_INVALID_VECTOR_SPACE);
        if (original.getType() == IntentType.INFORMATION
            && !vectorSpaceRejected
            && !Objects.equals(normalizedId(original.getVectorSpace()), normalizedId(effective.getVectorSpace()))) {
            return "Model recovery changed the knowledge source of a sibling obligation that was already valid";
        }
        return null;
    }

    private String actionId(Intent intent) {
        if (intent == null) {
            return null;
        }
        return normalizedId(StringUtils.hasText(intent.getAction())
            ? intent.getAction()
            : intent.getIntent());
    }

    private String normalizedId(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Map<String, Object> recoveryTransition(
        int intentIndex,
        Intent original,
        Intent effective,
        String decision,
        String outcome,
        String policyOrReason
    ) {
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("intentIndex", intentIndex);
        if (original != null && StringUtils.hasText(original.getObligationId())) {
            transition.put("obligationId", original.getObligationId().trim());
        }
        transition.put("decision", decision);
        transition.put("originalType", original != null && original.getType() != null
            ? original.getType().name()
            : "MISSING");
        if (StringUtils.hasText(actionId(original))) {
            transition.put("originalActionId", actionId(original));
        }
        transition.put("effectiveType", effective != null && effective.getType() != null
            ? effective.getType().name()
            : "MISSING");
        if (StringUtils.hasText(actionId(effective))) {
            transition.put("effectiveActionId", actionId(effective));
        }
        if (effective != null && StringUtils.hasText(effective.getVectorSpace())) {
            transition.put("effectiveVectorSpace", effective.getVectorSpace().trim());
        }
        transition.put("outcome", outcome);
        transition.put("policyOrReason", policyOrReason);
        return Collections.unmodifiableMap(transition);
    }

    private Map<String, Object> siblingPreservationTransition(
        int intentIndex,
        Intent original,
        Intent modelProposal,
        Intent effective,
        String reason
    ) {
        Map<String, Object> transition = new LinkedHashMap<>();
        transition.put("intentIndex", intentIndex);
        if (effective != null && StringUtils.hasText(effective.getObligationId())) {
            transition.put("obligationId", effective.getObligationId().trim());
        }
        transition.put("decision", "VALID_SIBLING_PRESERVATION");
        transition.put("originalType", original != null && original.getType() != null
            ? original.getType().name()
            : "MISSING");
        if (StringUtils.hasText(actionId(original))) {
            transition.put("originalActionId", actionId(original));
        }
        transition.put("modelProposedType", modelProposal != null
                && modelProposal.getType() != null
            ? modelProposal.getType().name()
            : "MISSING");
        if (StringUtils.hasText(actionId(modelProposal))) {
            transition.put("modelProposedActionId", actionId(modelProposal));
        }
        transition.put("effectiveType", effective != null && effective.getType() != null
            ? effective.getType().name()
            : "MISSING");
        if (StringUtils.hasText(actionId(effective))) {
            transition.put("effectiveActionId", actionId(effective));
        }
        transition.put("outcome", "SERVER_PRESERVED_VALID_SIBLING");
        transition.put("policyOrReason", reason);
        return Collections.unmodifiableMap(transition);
    }

    private ExtractionAttempt rejectedSemanticRecovery(
        ExtractionAttempt candidate,
        int intentIndex,
        String reason,
        List<Map<String, Object>> transitions
    ) {
        IntentExtractionValidator.ValidationIssue issue = new IntentExtractionValidator.ValidationIssue(
            IntentExtractionValidator.IssueCode.SEMANTIC_RECOVERY_NOT_ALLOWED,
            IntentExtractionValidator.Severity.ERROR,
            intentIndex,
            "type",
            reason
        );
        IntentExtractionValidator.ValidationResult validation = new IntentExtractionValidator.ValidationResult(
            false,
            IntentExtractionValidator.ErrorCategory.UNSAFE,
            List.of(issue),
            List.of(reason),
            List.of()
        );
        return ExtractionAttempt.builder()
            .success(false)
            .response(candidate.getResponse())
            .validationResult(validation)
            .rawContent(candidate.getRawContent())
            .generationRequest(candidate.getGenerationRequest())
            .errorMessage(reason)
            .exception(candidate.getException())
            .strategyName(candidate.getStrategyName())
            .llmCalls(candidate.getLlmCalls())
            .processingTimeMs(candidate.getProcessingTimeMs())
            .providerProcessingTimeMs(candidate.getProviderProcessingTimeMs())
            .model(candidate.getModel())
            .semanticRecoveryTransitions(List.copyOf(transitions))
            .build();
    }

    private ExtractionFailure extractionFailure(
        List<ExtractionAttempt> attempts
    ) {
        List<IntentExtractionValidator.IssueCode> issueCodes = issueCodes(attempts);
        boolean providerFailure = attempts != null && attempts.stream()
            .filter(Objects::nonNull)
            .map(ExtractionAttempt::getException)
            .filter(Objects::nonNull)
            .anyMatch(error -> hasCause(error, AIServiceException.class));
        if (providerFailure) {
            return new ExtractionFailure(
                PROVIDER_FAILURE_REASON,
                "The configured AI provider could not complete intent analysis.",
                true,
                issueCodes
            );
        }
        return new ExtractionFailure(
            EXTRACTION_FAILURE_REASON,
            "AI intent analysis did not produce a valid result.",
            false,
            issueCodes
        );
    }

    private List<IntentExtractionValidator.IssueCode> issueCodes(
        List<ExtractionAttempt> attempts
    ) {
        if (attempts == null || attempts.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<IntentExtractionValidator.IssueCode> issueCodes =
            new LinkedHashSet<>();
        attempts.stream()
            .filter(Objects::nonNull)
            .map(ExtractionAttempt::getValidationResult)
            .filter(Objects::nonNull)
            .map(IntentExtractionValidator.ValidationResult::issues)
            .filter(Objects::nonNull)
            .flatMap(List::stream)
            .filter(Objects::nonNull)
            .map(IntentExtractionValidator.ValidationIssue::code)
            .filter(Objects::nonNull)
            .forEach(issueCodes::add);
        return List.copyOf(issueCodes);
    }

    private boolean hasCause(
        Throwable error,
        Class<? extends Throwable> expectedType
    ) {
        Throwable current = error;
        while (current != null) {
            if (expectedType.isInstance(current)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
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
                                            String model,
                                            SemanticRecoveryPolicy recoveryPolicy) {
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
        out.put(
            "semanticDecision",
            semanticDecisionDiagnostics(path, attempts, llmCalls, model, recoveryPolicy)
        );
        return Collections.unmodifiableMap(out);
    }

    private Map<String, Object> semanticDecisionDiagnostics(
        String path,
        List<Map<String, Object>> attempts,
        int llmCalls,
        String model,
        SemanticRecoveryPolicy recoveryPolicy
    ) {
        List<Map<String, Object>> safeAttempts = attempts != null ? attempts : List.of();
        Map<String, Object> first = safeAttempts.isEmpty() ? Map.of() : safeAttempts.getFirst();
        Map<String, Object> last = safeAttempts.isEmpty() ? Map.of() : safeAttempts.getLast();
        boolean success = !"failed".equals(path) && Boolean.TRUE.equals(last.get("success"));
        boolean recoveryAttempted = safeAttempts.size() > 1;

        Map<String, Object> decision = new LinkedHashMap<>();
        decision.put("decision", "INTENT_EXTRACTION");
        decision.put("originalSource", "MODEL");
        decision.put("originalValue", attemptDecisionSummary(first));
        decision.put("validationStatus", validationStatus(first));
        decision.put("recoveryPolicy", recoveryPolicySummary(recoveryPolicy));
        decision.put("recoveryAttempted", recoveryAttempted);
        decision.put("effectiveSource", effectiveSource(path, success));
        decision.put("effectiveValue", success ? attemptDecisionSummary(last) : "FAILURE");
        decision.put("modelCalls", llmCalls);
        decision.put("outcome", success ? "VALIDATED" : "FAILED");
        if (StringUtils.hasText(model)) {
            decision.put("selectedModel", model);
        }
        return Collections.unmodifiableMap(decision);
    }

    private Map<String, Object> attemptDecisionSummary(Map<String, Object> attempt) {
        if (attempt == null || attempt.isEmpty()) {
            return Map.of("status", "NO_VALIDATED_MODEL_RESULT");
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        copyDiagnostic(attempt, summary, "strategy");
        copyDiagnostic(attempt, summary, "success");
        copyDiagnostic(attempt, summary, "errorCategory");
        copyDiagnostic(attempt, summary, "issueCodes");
        copyDiagnostic(attempt, summary, "intentCount");
        copyDiagnostic(attempt, summary, "intentTypes");
        copyDiagnostic(attempt, summary, "actionIds");
        copyDiagnostic(attempt, summary, "vectorSpaces");
        copyDiagnostic(attempt, summary, "obligationIds");
        return summary.isEmpty()
            ? Map.of("status", "NO_VALIDATED_MODEL_RESULT")
            : Collections.unmodifiableMap(summary);
    }

    private String validationStatus(Map<String, Object> attempt) {
        if (attempt == null || attempt.isEmpty()) {
            return "NO_MODEL_RESULT";
        }
        if (Boolean.TRUE.equals(attempt.get("success"))) {
            return "VALID";
        }
        Object category = attempt.get("errorCategory");
        return category != null ? "REJECTED_" + category : "REJECTED";
    }

    private Map<String, String> recoveryPolicySummary(SemanticRecoveryPolicy policy) {
        SemanticRecoveryPolicy safePolicy = policy != null
            ? policy
            : SemanticRecoveryPolicy.strictDefaults();
        Map<String, String> summary = new LinkedHashMap<>();
        summary.put("extractionFailure", safePolicy.extractionFailure().name());
        summary.put("missingTarget", safePolicy.missingTarget().name());
        summary.put("missingVectorSpace", safePolicy.missingVectorSpace().name());
        summary.put("invalidVectorSpace", safePolicy.invalidVectorSpace().name());
        summary.put("unknownAction", safePolicy.unknownAction().name());
        summary.put("actionDomainMismatch", safePolicy.actionDomainMismatch().name());
        summary.put("advancedNoEvidence", safePolicy.advancedNoEvidence().name());
        return Collections.unmodifiableMap(summary);
    }

    private String effectiveSource(String path, boolean success) {
        if (!success) {
            return "NONE";
        }
        return switch (path) {
            case "repair" -> "MODEL_REPAIR";
            case "completion" -> "MODEL_COMPLETION";
            case "multi_step" -> "MODEL_REPLAN";
            default -> "MODEL";
        };
    }

    private void copyDiagnostic(Map<String, Object> source,
                                Map<String, Object> target,
                                String key) {
        Object value = source.get(key);
        if (value != null) {
            target.put(key, value);
        }
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
            event.put(
                "errorMessage",
                IntentExtractionFailureSanitizer.isProviderFailure(
                    attempt.getException()
                )
                    ? IntentExtractionFailureSanitizer.PROVIDER_FAILURE_MESSAGE
                    : attempt.getErrorMessage()
            );
        }
        if (attempt.getResponse() != null && attempt.getResponse().getMetadata() != null) {
            Object normalization = attempt.getResponse().getMetadata().get("normalization");
            if (normalization != null) {
                event.put("normalization", normalization);
            }
        }
        if (attempt.getSemanticRecoveryTransitions() != null
            && !attempt.getSemanticRecoveryTransitions().isEmpty()) {
            event.put(
                "semanticRecoveryTransitions",
                List.copyOf(attempt.getSemanticRecoveryTransitions())
            );
        }
        addStructuredDecisionSummary(event, attempt.getResponse());
        return Collections.unmodifiableMap(event);
    }

    private void addStructuredDecisionSummary(Map<String, Object> event,
                                              MultiIntentResponse response) {
        if (event == null || response == null || response.getIntents() == null) {
            return;
        }
        List<Intent> intents = response.getIntents().stream()
            .filter(Objects::nonNull)
            .toList();
        event.put("intentCount", intents.size());
        putDistinctNonBlank(event, "intentTypes", intents.stream()
            .map(Intent::getType)
            .filter(Objects::nonNull)
            .map(Enum::name)
            .toList());
        putDistinctNonBlank(event, "actionIds", intents.stream()
            .map(intent -> StringUtils.hasText(intent.getAction())
                ? intent.getAction()
                : intent.getType() == IntentType.ACTION ? intent.getIntent() : null)
            .toList());
        putDistinctNonBlank(event, "vectorSpaces", intents.stream()
            .map(Intent::getVectorSpace)
            .toList());
        putDistinctNonBlank(event, "obligationIds", intents.stream()
            .map(Intent::getObligationId)
            .toList());
    }

    private void putDistinctNonBlank(Map<String, Object> target,
                                     String key,
                                     List<String> values) {
        List<String> normalized = values != null
            ? values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList()
            : List.of();
        if (!normalized.isEmpty()) {
            target.put(key, normalized);
        }
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
