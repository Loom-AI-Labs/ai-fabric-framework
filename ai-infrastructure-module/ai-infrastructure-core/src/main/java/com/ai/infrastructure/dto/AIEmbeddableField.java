package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

/**
 * AI Embeddable Field Configuration
 * 
 * Represents the configuration for an embeddable field.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AIEmbeddableField {
    
    private String name;
    private String model;
    private boolean autoGenerate;
    private boolean includeInSimilarity;
}
