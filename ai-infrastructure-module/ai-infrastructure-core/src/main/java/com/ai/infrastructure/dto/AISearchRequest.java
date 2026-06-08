package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;

import java.util.Map;

/**
 * Request DTO for AI semantic search
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AISearchRequest {
    
    @NotBlank(message = "Query cannot be blank")
    @Size(max = 1000, message = "Query cannot exceed 1000 characters")
    private String query;
    
    private String entityType;
    
    @Min(value = 1, message = "Limit must be at least 1")
    @Max(value = 100, message = "Limit cannot exceed 100")
    @Builder.Default
    private Integer limit = 10;
    
    @Builder.Default
    private Double threshold = 0.7;
    
    private String filters;
    
    private String sortBy;
    
    private String context;
    
    /**
     * Additional metadata for the request
     */
    private Map<String, Object> metadata;
}
