package com.ai.fabric.realapps.agenticresolver.agentic;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public specialist input. Identity and authority are deliberately absent.
 */
public record AccountResolutionRequest(
    @NotBlank @Size(max = 2_000) String question
) {
    public AccountResolutionRequest {
        question = question != null ? question.trim() : null;
    }
}
