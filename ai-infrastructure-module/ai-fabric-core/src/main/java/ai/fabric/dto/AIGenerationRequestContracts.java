package ai.fabric.dto;

import java.util.List;

/**
 * Shared validation for the framework's standard LLM request shape.
 */
public final class AIGenerationRequestContracts {

    private AIGenerationRequestContracts() {
    }

    public static void validateStandardChatPrompting(AIGenerationRequest request) {
        if (request == null || request.getMessages() == null || request.getMessages().isEmpty()) {
            return;
        }

        List<AIChatMessage> messages = request.getMessages();
        for (int i = 0; i < messages.size(); i++) {
            AIChatMessage message = messages.get(i);
            if (message == null) {
                throw new IllegalArgumentException("AIGenerationRequest.messages[" + i + "] cannot be null");
            }
            if (message.getRole() == null) {
                throw new IllegalArgumentException("AIGenerationRequest.messages[" + i + "].role is required");
            }
            if (AIChatRole.SYSTEM.equals(message.getRole())) {
                throw new IllegalArgumentException("AIGenerationRequest.messages[" + i
                    + "] must not use SYSTEM role. Use AIGenerationRequest.systemPrompt for system instructions.");
            }
            if (!hasText(message.getContent())) {
                throw new IllegalArgumentException("AIGenerationRequest.messages[" + i + "].content cannot be blank");
            }
        }

        if (!hasCurrentUserInput(request)) {
            throw new IllegalArgumentException("AIGenerationRequest.prompt or inputParts must provide the current user input when messages are present");
        }
    }

    private static boolean hasCurrentUserInput(AIGenerationRequest request) {
        if (hasText(request.getPrompt())) {
            return true;
        }
        if (request.getInputParts() == null || request.getInputParts().isEmpty()) {
            return false;
        }
        for (AIGenerationInputPart part : request.getInputParts()) {
            if (part == null || part.getType() == null) {
                continue;
            }
            if (AIGenerationInputType.TEXT.equals(part.getType()) && hasText(part.getText())) {
                return true;
            }
            if (AIGenerationInputType.FILE_URL.equals(part.getType()) && hasText(part.getUrl())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
