package ai.fabric.execution.chain;

import ai.fabric.execution.manager.ConversationManagerContextValue;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Immutable, approved state supplied to one manager decision. */
public record SpecialistChainManagerInput(
    String currentUserMessage,
    List<ConversationManagerContextValue> applicationContext,
    List<SpecialistChainTargetView> approvedTargets,
    List<SpecialistChainResultView> completedResults,
    SpecialistChainBudgetView remainingBudget,
    String previousDirectiveFeedback
) {
    public static final int MAX_MESSAGE_CHARACTERS = 4_000;
    public static final int MAX_CONTEXT_VALUES = 16;
    public static final int MAX_TARGETS = 8;
    public static final int MAX_RESULTS = 8;
    public static final int MAX_DIRECTIVE_FEEDBACK_CHARACTERS = 5_000;

    public SpecialistChainManagerInput {
        currentUserMessage = Objects.requireNonNull(
            currentUserMessage,
            "currentUserMessage is required"
        ).trim();
        if (currentUserMessage.isEmpty()) {
            throw new IllegalArgumentException(
                "currentUserMessage is required"
            );
        }
        if (currentUserMessage.length() > MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                "currentUserMessage exceeds the chain-manager limit"
            );
        }
        applicationContext = applicationContext == null
            ? List.of()
            : List.copyOf(applicationContext);
        if (applicationContext.size() > MAX_CONTEXT_VALUES) {
            throw new IllegalArgumentException(
                "applicationContext exceeds the chain-manager limit"
            );
        }
        Set<String> contextNames = new HashSet<>();
        for (ConversationManagerContextValue context : applicationContext) {
            ConversationManagerContextValue required = Objects.requireNonNull(
                context,
                "application context value is required"
            );
            if (!contextNames.add(required.name())) {
                throw new IllegalArgumentException(
                    "applicationContext contains duplicate name "
                        + required.name()
                );
            }
        }
        approvedTargets = approvedTargets == null
            ? List.of()
            : List.copyOf(approvedTargets);
        if (approvedTargets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException(
                "approvedTargets must not exceed " + MAX_TARGETS
                    + " targets"
            );
        }
        Set<String> targetIds = new HashSet<>();
        for (SpecialistChainTargetView target : approvedTargets) {
            SpecialistChainTargetView required = Objects.requireNonNull(
                target,
                "approved target is required"
            );
            if (!targetIds.add(required.specialist())) {
                throw new IllegalArgumentException(
                    "approvedTargets contains duplicate specialist"
                );
            }
        }
        completedResults = completedResults == null
            ? List.of()
            : List.copyOf(completedResults);
        if (completedResults.size() > MAX_RESULTS) {
            throw new IllegalArgumentException(
                "completedResults exceeds the chain-manager limit"
            );
        }
        Set<String> resultIds = new HashSet<>();
        for (SpecialistChainResultView result : completedResults) {
            SpecialistChainResultView required = Objects.requireNonNull(
                result,
                "completed result is required"
            );
            if (!resultIds.add(required.resultId())) {
                throw new IllegalArgumentException(
                    "completedResults contains duplicate resultId"
                );
            }
        }
        Objects.requireNonNull(
            remainingBudget,
            "remainingBudget is required"
        );
        previousDirectiveFeedback = normalizeOptional(
            previousDirectiveFeedback
        );
        if (previousDirectiveFeedback != null
            && previousDirectiveFeedback.length()
                > MAX_DIRECTIVE_FEEDBACK_CHARACTERS) {
            throw new IllegalArgumentException(
                "previousDirectiveFeedback exceeds the chain-manager limit"
            );
        }
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
