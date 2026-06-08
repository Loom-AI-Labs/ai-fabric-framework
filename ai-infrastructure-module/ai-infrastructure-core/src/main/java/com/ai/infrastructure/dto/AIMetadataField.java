package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

/**
 * AI Metadata Field Configuration
 * 
 * Represents the configuration for a metadata field.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AIMetadataField {
    
    private String name;
    private String type;
    private boolean includeInSearch;
}
