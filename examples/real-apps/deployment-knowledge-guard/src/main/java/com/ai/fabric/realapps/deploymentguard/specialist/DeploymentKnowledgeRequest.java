package com.ai.fabric.realapps.deploymentguard.specialist;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = false)
public record DeploymentKnowledgeRequest(
    @NotBlank @Size(max = 2_000) String question
) {
    public DeploymentKnowledgeRequest {
        question = question == null ? null : question.trim();
    }

    @JsonAnySetter
    public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException(
            "Unknown deployment knowledge request field: " + name
        );
    }
}
