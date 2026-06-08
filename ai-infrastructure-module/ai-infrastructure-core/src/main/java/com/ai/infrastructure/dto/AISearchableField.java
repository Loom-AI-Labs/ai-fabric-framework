package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;

/**
 * AI Searchable Field Configuration
 * 
 * Represents the configuration for a searchable field.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AISearchableField {
    
    private String name;
    private boolean includeInRAG;
    private boolean enableSemanticSearch;
    private double weight;
}
