package com.ai.fabric.runtime.web.dto;

public record RuntimeShellStarterPromptResponse(
    String id,
    String label,
    String query,
    String moduleId,
    String cardId
) {
}
