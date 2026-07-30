package com.ai.fabric.realapps.agenticresolver.agentic;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Public specialist input. Identity and authority are deliberately absent.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public record AccountResolutionRequest(
    @NotBlank @Size(max = 2_000) String question
) {
    public AccountResolutionRequest {
        question = question != null ? question.trim() : null;
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException(
            "Unknown account resolution request field: " + name
        );
    }
}
