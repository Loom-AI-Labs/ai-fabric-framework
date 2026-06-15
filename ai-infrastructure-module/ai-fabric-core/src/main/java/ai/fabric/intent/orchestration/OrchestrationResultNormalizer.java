package ai.fabric.intent.orchestration;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalize {@link OrchestrationResult} into a provider-agnostic, deterministic contract.
 *
 * <p>This component is intentionally conservative: it only normalizes outcomes where the
 * system has deterministic facts (e.g., missing action handler, any child ERROR).</p>
 */
@Component
@RequiredArgsConstructor
public class OrchestrationResultNormalizer {

    // Canonical error codes (kept as strings to avoid leaking new public enums)
    public static final String ERROR_CODE_ACTION_NOT_FOUND = "ACTION_NOT_FOUND";
    public static final String ERROR_CODE_CHILD_ERROR = "CHILD_ERROR";
    public static final String ERROR_CODE_GENERATION_FAILED = "GENERATION_FAILED";

    private static final String METADATA_KEY_COMPOUND_AGGREGATION = "compoundAggregation";

    /**
     * Normalize the given result. If no normalization is applicable, returns the original result.
     */
    public OrchestrationResult normalize(OrchestrationResult raw) {
        if (raw == null) {
            return null;
        }

        // Rule: treat "pending" compound children (confirmation/clarification) as pending rather than "failed".
        // This prevents misleading wrappers like "Some intents failed" when intents are simply awaiting user input.
        if (raw.getType() == OrchestrationResultType.COMPOUND_HANDLED) {
            CompoundAggregation aggregation = aggregateCompound(raw.getChildren());
            if (aggregation != null && aggregation.pendingCount() > 0) {
                // If the compound includes a child ERROR, preserve existing deterministic bubbling behavior.
                OrchestrationResult childError = firstChildError(raw.getChildren());
                if (childError != null) {
                    String errorCode = StringUtils.hasText(childError.getErrorCode())
                        ? childError.getErrorCode()
                        : deriveErrorCodeFromMessage(childError.getMessage());
                    if (!StringUtils.hasText(errorCode)) {
                        errorCode = ERROR_CODE_CHILD_ERROR;
                    }

                    // Soft child error: keep compound wrapper (do not promote primary) so pending children remain visible.
                    if (ERROR_CODE_ACTION_NOT_FOUND.equals(errorCode) || isGenerationFailure(childError)) {
                        String softCode = ERROR_CODE_ACTION_NOT_FOUND.equals(errorCode)
                            ? errorCode
                            : ERROR_CODE_GENERATION_FAILED;
                        Map<String, Object> metadata = withSoftChildErrorMetadata(raw.getMetadata(), softCode);
                        metadata = withCompoundAggregationMetadata(metadata,
                            aggregation,
                            OrchestrationResultType.COMPOUND_HANDLED,
                            "pending_with_soft_error_keep_compound");
                        return OrchestrationResult.builder()
                            .type(OrchestrationResultType.COMPOUND_HANDLED)
                            .success(false)
                            .message(buildCompoundMessage(aggregation))
                            .errorCode(raw.getErrorCode())
                            .data(raw.getData())
                            .children(raw.getChildren())
                            .nextSteps(raw.getNextSteps())
                            .metadata(metadata)
                            .smartSuggestion(raw.getSmartSuggestion())
                            .build();
                    }

                    // Hard child error: bubble deterministically.
                    return bubbleChildError(raw, childError, errorCode);
                }

                Map<String, Object> metadata = withCompoundAggregationMetadata(raw.getMetadata(),
                    aggregation,
                    OrchestrationResultType.COMPOUND_HANDLED,
                    aggregation.ruleApplied());

                // All pending: return the next pending item at top-level (one-at-a-time UX),
                // while preserving all children for auditing/debug.
                if (aggregation.successCount() == 0 && aggregation.failureCount() == 0) {
                    OrchestrationResult next = aggregation.nextPending();
                    OrchestrationResultType topType = aggregation.pendingTopType();
                    metadata = withCompoundAggregationMetadata(metadata, aggregation, topType, aggregation.ruleApplied());
                    if (next != null) {
                        Map<String, Object> data = next.getData();
                        return OrchestrationResult.builder()
                            .type(topType)
                            .success(false)
                            .message(StringUtils.hasText(next.getMessage())
                                ? next.getMessage()
                                : (topType == OrchestrationResultType.CONFIRMATION_REQUIRED
                                    ? "Confirmation required to proceed."
                                    : "More information required to proceed."))
                            .errorCode(raw.getErrorCode())
                            .data(data)
                            .children(raw.getChildren())
                            .nextSteps(raw.getNextSteps())
                            .metadata(metadata)
                            .smartSuggestion(raw.getSmartSuggestion())
                            .build();
                    }

                    // Defensive fallback: keep compound but correct the message.
                    return OrchestrationResult.builder()
                        .type(OrchestrationResultType.COMPOUND_HANDLED)
                        .success(false)
                        .message(buildCompoundMessage(aggregation))
                        .errorCode(raw.getErrorCode())
                        .data(raw.getData())
                        .children(raw.getChildren())
                        .nextSteps(raw.getNextSteps())
                        .metadata(withCompoundAggregationMetadata(metadata,
                            aggregation,
                            OrchestrationResultType.COMPOUND_HANDLED,
                            aggregation.ruleApplied()))
                        .smartSuggestion(raw.getSmartSuggestion())
                        .build();
                }

                // Mix of pending + successes (no failures): keep compound visible (do not promote primary).
                if (aggregation.failureCount() == 0 && aggregation.successCount() > 0) {
                    return OrchestrationResult.builder()
                        .type(OrchestrationResultType.COMPOUND_HANDLED)
                        .success(true)
                        .message(buildCompoundMessage(aggregation))
                        .errorCode(raw.getErrorCode())
                        .data(raw.getData())
                        .children(raw.getChildren())
                        .nextSteps(raw.getNextSteps())
                        .metadata(withCompoundAggregationMetadata(metadata,
                            aggregation,
                            OrchestrationResultType.COMPOUND_HANDLED,
                            aggregation.ruleApplied()))
                        .smartSuggestion(raw.getSmartSuggestion())
                        .build();
                }

                // Pending + failures: keep compound and mark failure.
                return OrchestrationResult.builder()
                    .type(OrchestrationResultType.COMPOUND_HANDLED)
                    .success(false)
                    .message(buildCompoundMessage(aggregation))
                    .errorCode(raw.getErrorCode())
                    .data(raw.getData())
                    .children(raw.getChildren())
                    .nextSteps(raw.getNextSteps())
                    .metadata(withCompoundAggregationMetadata(metadata,
                        aggregation,
                        OrchestrationResultType.COMPOUND_HANDLED,
                        aggregation.ruleApplied()))
                    .smartSuggestion(raw.getSmartSuggestion())
                    .build();
            }
        }

        // Rule: normalize compound wrappers into a stable top-level type.
        // IMPORTANT: a compound request may contain a successful primary child plus a non-primary child ERROR
        // (commonly ACTION_NOT_FOUND from a follow-up "summarize/explain" misclassified as ACTION).
        // We must not sink the entire request to ERROR in that case.
        if (raw.getType() == OrchestrationResultType.COMPOUND_HANDLED) {
            OrchestrationResult primary = choosePrimaryChild(raw.getChildren());
            if (primary != null && primary.getType() != null && primary.getType() != OrchestrationResultType.ERROR) {
                OrchestrationResult childError = firstChildError(raw.getChildren());
                if (childError != null) {
                    String errorCode = StringUtils.hasText(childError.getErrorCode())
                        ? childError.getErrorCode()
                        : deriveErrorCodeFromMessage(childError.getMessage());
                    if (!StringUtils.hasText(errorCode)) {
                        errorCode = ERROR_CODE_CHILD_ERROR;
                    }

                    // Soft child error: do not sink the compound when primary succeeded.
                    if (ERROR_CODE_ACTION_NOT_FOUND.equals(errorCode) || isGenerationFailure(childError)) {
                        String softCode = ERROR_CODE_ACTION_NOT_FOUND.equals(errorCode)
                            ? errorCode
                            : ERROR_CODE_GENERATION_FAILED;
                        Map<String, Object> metadata = withSoftChildErrorMetadata(raw.getMetadata(), softCode);
                        return promoteCompoundPrimary(raw, primary, metadata);
                    }

                    // Hard child error: bubble deterministically.
                    return bubbleChildError(raw, childError, errorCode);
                }

                return promoteCompoundPrimary(raw, primary, raw.getMetadata());
            }
        }

        // Rule: bubble any remaining child error to a top-level ERROR deterministically.
        OrchestrationResult childError = firstChildError(raw.getChildren());
        if (childError != null) {
            String errorCode = StringUtils.hasText(childError.getErrorCode())
                ? childError.getErrorCode()
                : deriveErrorCodeFromMessage(childError.getMessage());

            if (!StringUtils.hasText(errorCode)) {
                errorCode = ERROR_CODE_CHILD_ERROR;
            }

            return bubbleChildError(raw, childError, errorCode);
        }

        // Rule: if the result is already an ERROR but lacks errorCode, try to derive.
        if (raw.getType() == OrchestrationResultType.ERROR && !StringUtils.hasText(raw.getErrorCode())) {
            String derived = deriveErrorCodeFromDataOrMessage(raw.getData(), raw.getMessage());
            if (StringUtils.hasText(derived)) {
                raw.setErrorCode(derived);
                raw.setMetadata(withErrorCodeMetadata(raw.getMetadata(), derived));
            }
        }

        return raw;
    }

    private boolean isPendingType(OrchestrationResultType type) {
        return type == OrchestrationResultType.CONFIRMATION_REQUIRED
            || type == OrchestrationResultType.CLARIFICATION_REQUIRED;
    }

    private String buildCompoundMessage(CompoundAggregation aggregation) {
        if (aggregation == null) {
            return "Request processed.";
        }

        if (aggregation.failureCount() > 0) {
            if (aggregation.pendingCount() > 0) {
                return "Some items failed; others require confirmation or clarification.";
            }
            return "Some items failed. See results for details.";
        }

        if (aggregation.pendingCount() > 0 && aggregation.successCount() > 0) {
            return "Some items completed; others require confirmation or clarification.";
        }

        if (aggregation.pendingCount() > 0) {
            return aggregation.pendingTopType() == OrchestrationResultType.CONFIRMATION_REQUIRED
                ? "Confirmation required."
                : "More information required.";
        }

        return "All items processed successfully.";
    }

    private CompoundAggregation aggregateCompound(List<OrchestrationResult> children) {
        if (children == null || children.isEmpty()) {
            return null;
        }

        int successCount = 0;
        int pendingCount = 0;
        int failureCount = 0;

        boolean hasConfirmation = false;
        OrchestrationResult lastConfirmation = null;
        OrchestrationResult lastClarification = null;

        for (OrchestrationResult child : children) {
            if (child == null || child.getType() == null) {
                continue;
            }

            OrchestrationResultType type = child.getType();
            if (isPendingType(type)) {
                pendingCount++;
                if (type == OrchestrationResultType.CONFIRMATION_REQUIRED) {
                    hasConfirmation = true;
                    lastConfirmation = child;
                } else {
                    lastClarification = child;
                }
                continue;
            }

            if (child.isSuccess()) {
                successCount++;
                continue;
            }

            // Non-pending, non-success is treated as failure (includes ACTION_DENIED, unsuccessful ACTION_EXECUTED, etc.).
            failureCount++;
        }

        OrchestrationResult nextPending = hasConfirmation ? lastConfirmation : lastClarification;
        OrchestrationResultType pendingTopType = hasConfirmation
            ? OrchestrationResultType.CONFIRMATION_REQUIRED
            : OrchestrationResultType.CLARIFICATION_REQUIRED;

        String ruleApplied = pendingCount > 0 && successCount == 0 && failureCount == 0
            ? "all_pending_promote_next"
            : pendingCount > 0 && failureCount == 0
                ? "pending_and_success_keep_compound"
                : "pending_and_failure_keep_compound";

        return new CompoundAggregation(successCount, pendingCount, failureCount, pendingTopType, nextPending, ruleApplied);
    }

    private Map<String, Object> withCompoundAggregationMetadata(Map<String, Object> existing,
                                                                CompoundAggregation aggregation,
                                                                OrchestrationResultType topLevelType,
                                                                String ruleApplied) {
        if (aggregation == null) {
            return existing;
        }

        Map<String, Object> merged = new LinkedHashMap<>(existing != null ? existing : Map.of());
        merged.put(METADATA_KEY_COMPOUND_AGGREGATION, Map.of(
            "successCount", aggregation.successCount(),
            "pendingCount", aggregation.pendingCount(),
            "failureCount", aggregation.failureCount(),
            "topLevelType", String.valueOf(topLevelType),
            "ruleApplied", ruleApplied
        ));
        return Map.copyOf(merged);
    }

    private record CompoundAggregation(
        int successCount,
        int pendingCount,
        int failureCount,
        OrchestrationResultType pendingTopType,
        OrchestrationResult nextPending,
        String ruleApplied
    ) {}

    private OrchestrationResult bubbleChildError(OrchestrationResult raw, OrchestrationResult childError, String errorCode) {
        String message = StringUtils.hasText(childError.getMessage())
            ? childError.getMessage()
            : (StringUtils.hasText(raw.getMessage()) ? raw.getMessage() : "An error occurred.");

        Map<String, Object> metadata = withErrorCodeMetadata(raw.getMetadata(), errorCode);
        return OrchestrationResult.builder()
            .type(OrchestrationResultType.ERROR)
            .success(false)
            .message(message)
            .errorCode(errorCode)
            // preserve details for debugging/auditing
            .data(raw.getData())
            .children(raw.getChildren())
            .nextSteps(raw.getNextSteps())
            .metadata(metadata)
            .smartSuggestion(raw.getSmartSuggestion())
            .build();
    }

    private OrchestrationResult promoteCompoundPrimary(OrchestrationResult raw,
                                                       OrchestrationResult primary,
                                                       Map<String, Object> metadata) {
        OrchestrationResultType normalizedType = primary.getType();
        boolean normalizedSuccess = switch (normalizedType) {
            case ACTION_EXECUTED, INFORMATION_PROVIDED -> primary.isSuccess();
            case CLARIFICATION_REQUIRED -> false;
            case OUT_OF_SCOPE -> true;
            case ACTION_DENIED -> false;
            default -> raw.isSuccess();
        };

        String message = StringUtils.hasText(primary.getMessage()) ? primary.getMessage() : raw.getMessage();
        Map<String, Object> data = (primary.getData() != null && !primary.getData().isEmpty())
            ? primary.getData()
            : raw.getData();

        return OrchestrationResult.builder()
            .type(normalizedType)
            .success(normalizedSuccess)
            .message(message)
            .errorCode(raw.getErrorCode())
            .data(data)
            .children(raw.getChildren())
            .nextSteps(raw.getNextSteps())
            .metadata(metadata)
            .smartSuggestion(raw.getSmartSuggestion())
            .build();
    }

    private OrchestrationResult firstChildError(List<OrchestrationResult> children) {
        if (children == null || children.isEmpty()) {
            return null;
        }
        return children.stream()
            .filter(child -> child != null && child.getType() == OrchestrationResultType.ERROR)
            .findFirst()
            .orElse(null);
    }

    private OrchestrationResult choosePrimaryChild(List<OrchestrationResult> children) {
        if (children == null || children.isEmpty()) {
            return null;
        }

        // Priority order: actions > information > scope (provider-agnostic and stable).
        return firstByType(children, OrchestrationResultType.ACTION_EXECUTED,
            OrchestrationResultType.ACTION_DENIED,
            OrchestrationResultType.INFORMATION_PROVIDED,
            OrchestrationResultType.CLARIFICATION_REQUIRED,
            OrchestrationResultType.OUT_OF_SCOPE,
            OrchestrationResultType.COMPOUND_HANDLED);
    }

    private OrchestrationResult firstByType(List<OrchestrationResult> children, OrchestrationResultType... types) {
        for (OrchestrationResultType type : types) {
            for (OrchestrationResult child : children) {
                if (child != null && child.getType() == type) {
                    return child;
                }
            }
        }
        return null;
    }

    private String deriveErrorCodeFromDataOrMessage(Map<String, Object> data, String message) {
        String fromActionResult = extractActionResultErrorCode(data);
        if (StringUtils.hasText(fromActionResult)) {
            return fromActionResult;
        }
        return deriveErrorCodeFromMessage(message);
    }

    private String extractActionResultErrorCode(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return null;
        }
        Object actionResult = data.get("actionResult");
        if (actionResult instanceof ai.fabric.intent.action.ActionResult ar) {
            return ar.getErrorCode();
        }
        if (actionResult instanceof Map<?, ?> map) {
            Object code = map.get("errorCode");
            return code != null ? String.valueOf(code) : null;
        }
        return null;
    }

    private String deriveErrorCodeFromMessage(String message) {
        if (!StringUtils.hasText(message)) {
            return null;
        }
        String m = message.toLowerCase();
        if (m.contains("no action handler registered")) {
            return ERROR_CODE_ACTION_NOT_FOUND;
        }
        return null;
    }

    private Map<String, Object> withErrorCodeMetadata(Map<String, Object> existing, String errorCode) {
        if (!StringUtils.hasText(errorCode)) {
            return existing;
        }
        Map<String, Object> merged = new LinkedHashMap<>(existing != null ? existing : Map.of());
        merged.put("errorCode", errorCode);
        return Map.copyOf(merged);
    }

    private Map<String, Object> withSoftChildErrorMetadata(Map<String, Object> existing, String errorCode) {
        if (!StringUtils.hasText(errorCode)) {
            return existing;
        }
        Map<String, Object> merged = new LinkedHashMap<>(existing != null ? existing : Map.of());
        merged.put("softChildErrorCode", errorCode);
        return Map.copyOf(merged);
    }

    private boolean isGenerationFailure(OrchestrationResult childError) {
        if (childError == null || childError.getType() != OrchestrationResultType.ERROR) {
            return false;
        }
        // Signal emitted by IntentHandlingStep.handleInformationBasic() when LLM generation fails.
        Map<String, Object> data = childError.getData();
        return data != null && data.containsKey("generationError");
    }
}
