package com.ai.fabric.runtime.web.dto;

import com.ai.infrastructure.intent.orchestration.attachment.OrchestrationAttachment;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class ChatQueryRequest {

    @NotBlank
    private String query;

    private String conversationId;
    private String position;
    private String mode;
    private Map<String, Object> context;
    private List<OrchestrationAttachment> attachments;
    private Map<String, String> promptPreview;

    @JsonIgnore
    @Schema(hidden = true)
    private final Map<String, Object> unexpectedFields = new LinkedHashMap<>();

    @JsonAnySetter
    void captureUnexpectedField(String name, Object value) {
        unexpectedFields.put(name, value);
    }
}
