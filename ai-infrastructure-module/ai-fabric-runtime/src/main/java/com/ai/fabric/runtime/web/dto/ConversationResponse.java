package com.ai.fabric.runtime.web.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class ConversationResponse {
    private String id;
    private String status;
    private LocalDateTime createdAt;
    private LocalDateTime lastInteractionAt;
    private RuntimeAuthContextResponse authContext;
    private List<TurnResponse> turns;
}
