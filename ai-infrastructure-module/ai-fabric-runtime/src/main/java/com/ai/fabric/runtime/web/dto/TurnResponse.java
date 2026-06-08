package com.ai.fabric.runtime.web.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TurnResponse {
    private LocalDateTime timestamp;
    private String userQuery;
    private String aiResponse;
}

