package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

/**
 * AI CRUD Operation Configuration
 * 
 * Represents the configuration for a CRUD operation.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AICrudOperation {
    
    private String operation;
    private boolean generateEmbedding;
    private boolean indexForSearch;
    private boolean enableAnalysis;
    private boolean removeFromSearch;
    private boolean cleanupEmbeddings;
}
