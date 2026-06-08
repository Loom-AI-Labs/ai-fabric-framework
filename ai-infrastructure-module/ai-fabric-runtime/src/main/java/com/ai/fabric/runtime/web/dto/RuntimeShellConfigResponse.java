package com.ai.fabric.runtime.web.dto;

import java.util.List;

public record RuntimeShellConfigResponse(
    boolean success,
    String contractVersion,
    String greetingTitle,
    String greetingMessage,
    List<RuntimeShellStarterPromptResponse> starterPrompts,
    List<String> moduleIds,
    List<String> cardIds,
    List<String> supportedModuleIds,
    List<String> supportedCardIds,
    List<String> supportedEvidenceBlockIds
) {
}
