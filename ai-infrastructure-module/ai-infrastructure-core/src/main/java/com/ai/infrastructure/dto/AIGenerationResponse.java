package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * AIGenerationResponse
 * 
 * Response DTO for AI content generation operations.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AIGenerationResponse {
    
    private String id;
    
    private String requestId;
    
    private String entityId;
    
    private String entityType;
    
    private String generationType;
    
    private String content;
    
    private String model;
    
    private Integer tokensUsed;
    
    private Double confidence;
    
    private Object usage;
    
    private Long processingTimeMs;
    
    private Map<String, Object> metadata;
    
    private LocalDateTime generatedAt;
    
    private String status;
    
    private String error;
}