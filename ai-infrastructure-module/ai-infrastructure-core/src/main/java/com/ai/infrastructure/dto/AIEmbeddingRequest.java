package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for AI embedding generation
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AIEmbeddingRequest {
    
    @NotBlank(message = "Text cannot be blank")
    @Size(max = 8000, message = "Text cannot exceed 8000 characters")
    private String text;
    
    private String model;
    
    private String entityType;
    
    private String entityId;
    
    private String metadata;
}
