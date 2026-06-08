package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Response DTO for AI semantic search
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AISearchResponse {
    
    private List<Map<String, Object>> results;
    
    private Integer totalResults;
    
    private Double maxScore;
    
    private Long processingTimeMs;
    
    private String requestId;
    
    private String query;
    
    private String model;
}
