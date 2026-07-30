package ai.fabric.intent.orchestration.conversation;

import ai.fabric.dto.AIChatMessage;
import ai.fabric.dto.AIChatRole;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable backend-approved conversation projection for one interactive turn.
 */
public record ApprovedConversationSnapshot(
    String interactionTurnId,
    String ownerId,
    String conversationId,
    String dialogueOwnerSpecialist,
    String revision,
    long sourceTurnCount,
    List<AIChatMessage> historyMessages,
    Instant capturedAt
) {
    private static final int MAX_MESSAGES = 100;
    private static final int MAX_CHARACTERS = 100_000;
    private static final Pattern REVISION_PATTERN =
        Pattern.compile("^[a-f0-9]{64}$");

    public ApprovedConversationSnapshot {
        interactionTurnId = requireText(
            interactionTurnId,
            "interactionTurnId"
        );
        ownerId = requireText(ownerId, "ownerId");
        conversationId = requireText(conversationId, "conversationId");
        dialogueOwnerSpecialist = requireText(
            dialogueOwnerSpecialist,
            "dialogueOwnerSpecialist"
        );
        revision = requireText(revision, "revision").toLowerCase();
        if (!REVISION_PATTERN.matcher(revision).matches()) {
            throw new IllegalArgumentException(
                "revision must be a SHA-256 hexadecimal value"
            );
        }
        if (sourceTurnCount < 0) {
            throw new IllegalArgumentException(
                "sourceTurnCount cannot be negative"
            );
        }
        historyMessages = copyMessages(historyMessages);
        Objects.requireNonNull(capturedAt, "capturedAt is required");
    }

    @Override
    public List<AIChatMessage> historyMessages() {
        return defensiveCopy(historyMessages);
    }

    private static List<AIChatMessage> copyMessages(
        List<AIChatMessage> messages
    ) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        if (messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException(
                "historyMessages exceeds the approved message limit"
            );
        }
        int characters = 0;
        java.util.ArrayList<AIChatMessage> copy =
            new java.util.ArrayList<>(messages.size());
        for (AIChatMessage message : messages) {
            if (message == null
                || (message.getRole() != AIChatRole.USER
                    && message.getRole() != AIChatRole.ASSISTANT)
                || message.getContent() == null
                || message.getContent().isBlank()) {
                throw new IllegalArgumentException(
                    "historyMessages must contain non-blank user or assistant messages"
                );
            }
            String content = message.getContent();
            characters += content.length();
            if (characters > MAX_CHARACTERS) {
                throw new IllegalArgumentException(
                    "historyMessages exceeds the approved character limit"
                );
            }
            copy.add(new AIChatMessage(message.getRole(), content));
        }
        return List.copyOf(copy);
    }

    private static List<AIChatMessage> defensiveCopy(
        List<AIChatMessage> messages
    ) {
        if (messages.isEmpty()) {
            return List.of();
        }
        return messages.stream()
            .map(message -> new AIChatMessage(
                message.getRole(),
                message.getContent()
            ))
            .toList();
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
