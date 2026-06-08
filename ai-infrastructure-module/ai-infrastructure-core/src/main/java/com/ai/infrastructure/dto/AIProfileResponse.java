package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Generic AI Profile Response DTO
 * 
 * Response DTO for AI profile operations.
 * This DTO is domain-agnostic and can be used across different applications.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIProfileResponse {
    
    private String id;
    private String userId;
    private String preferences;
    private String interests;
    private String behaviorPatterns;
    private String cvFileUrl;
    private String status;
    private Double confidenceScore;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Map<String, Object> additionalData;
}