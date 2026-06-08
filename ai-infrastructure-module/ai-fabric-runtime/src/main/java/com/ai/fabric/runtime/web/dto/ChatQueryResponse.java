package com.ai.fabric.runtime.web.dto;

import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatQueryResponse {
    private boolean success;
    private String type;
    private String answer;
    private String safeSummary;
    @JsonIgnore
    private String message;
    private String conversationId;
    private String mode;
    private String position;
    @Builder.Default
    private List<Object> sources = List.of();
    @Builder.Default
    private Map<String, Object> ragResponse = Map.of();
    @Builder.Default
    private List<Object> actions = List.of();
    @Builder.Default
    private List<Object> suggestions = List.of();
    private String fallbackReason;
    private String providerRequestId;
    @Builder.Default
    private Map<String, Object> metadata = Map.of();
    @JsonIgnore
    private String sessionId;
    @JsonIgnore
    private RuntimeAuthContextResponse authContext;
    @JsonIgnore
    private OrchestrationResult result;
}
