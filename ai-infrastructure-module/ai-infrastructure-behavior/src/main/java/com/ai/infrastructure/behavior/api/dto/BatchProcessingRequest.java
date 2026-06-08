package com.ai.infrastructure.behavior.api.dto;

import lombok.Data;

@Data
public class BatchProcessingRequest {
    private Integer maxUsers;
    private Integer maxDurationMinutes;
    private Long delayBetweenUsersMs;
}
