package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotNull;
import java.util.Map;

/**
 * Generic AI Profile Request DTO
 * 
 * Request DTO for AI profile operations.
 * This DTO is domain-agnostic and can be used across different applications.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIProfileRequest {
    
    @NotNull(message = "User ID is required")
    private String userId;
    
    private String preferences;
    private String interests;
    private String behaviorPatterns;
    private String cvFileUrl;
    private String status;
    private Double confidenceScore;
    private Integer version;
    private Map<String, Object> additionalData;
}