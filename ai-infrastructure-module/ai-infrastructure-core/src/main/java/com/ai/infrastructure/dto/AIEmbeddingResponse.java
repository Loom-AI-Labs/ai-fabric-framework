package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response DTO for AI embedding generation
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AIEmbeddingResponse {
    
    private List<Double> embedding;
    
    private String model;
    
    private Integer dimensions;
    
    private Long processingTimeMs;
    
    private String requestId;
}
