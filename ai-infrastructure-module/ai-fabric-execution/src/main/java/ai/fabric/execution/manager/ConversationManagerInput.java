package ai.fabric.execution.manager;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Approved manager input. Conversation history remains in the frozen
 * orchestration snapshot rather than this public DTO.
 */
public record ConversationManagerInput(
    String currentUserMessage,
    List<ConversationManagerContextValue> applicationContext,
    List<ConversationManagerTargetView> approvedTargets
) {
    public static final int MAX_MESSAGE_CHARACTERS = 4000;
    public static final int MAX_CONTEXT_VALUES = 16;
    public static final int MAX_TARGETS = 8;

    public ConversationManagerInput {
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
                "currentUserMessage must not exceed "
                    + MAX_MESSAGE_CHARACTERS + " characters"
            );
        }
        applicationContext = applicationContext == null
            ? List.of()
            : List.copyOf(applicationContext);
        if (applicationContext.size() > MAX_CONTEXT_VALUES) {
            throw new IllegalArgumentException(
                "applicationContext must not exceed "
                    + MAX_CONTEXT_VALUES + " values"
            );
        }
        Set<String> contextNames = new HashSet<>();
        for (ConversationManagerContextValue value : applicationContext) {
            ConversationManagerContextValue required = Objects.requireNonNull(
                value,
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
        if (approvedTargets.isEmpty()) {
            throw new IllegalArgumentException(
                "approvedTargets must not be empty"
            );
        }
        if (approvedTargets.size() > MAX_TARGETS) {
            throw new IllegalArgumentException(
                "approvedTargets must not exceed " + MAX_TARGETS
            );
        }
        approvedTargets.forEach(value ->
            Objects.requireNonNull(value, "approved target is required")
        );
    }
}
