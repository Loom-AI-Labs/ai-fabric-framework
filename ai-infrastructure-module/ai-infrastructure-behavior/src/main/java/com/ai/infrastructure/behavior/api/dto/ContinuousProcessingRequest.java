package com.ai.infrastructure.behavior.api.dto;

import lombok.Data;

@Data
public class ContinuousProcessingRequest {
    private Integer usersPerBatch;
    private Integer intervalMinutes;
    private Integer maxIterations;
}
