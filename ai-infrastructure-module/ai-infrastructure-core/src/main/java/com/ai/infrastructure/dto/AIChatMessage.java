package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Provider-agnostic chat message for multi-message LLM prompting.
 *
 * <p>This DTO is used for passing conversation history to LLM providers via their
 * native chat formats, instead of packing history into a single prompt string.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIChatMessage {

    private AIChatRole role;

    private String content;

    public static AIChatMessage system(String content) {
        return AIChatMessage.builder().role(AIChatRole.SYSTEM).content(content).build();
    }

    public static AIChatMessage user(String content) {
        return AIChatMessage.builder().role(AIChatRole.USER).content(content).build();
    }

    public static AIChatMessage assistant(String content) {
        return AIChatMessage.builder().role(AIChatRole.ASSISTANT).content(content).build();
    }
}

