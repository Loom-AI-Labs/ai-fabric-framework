package com.ai.infrastructure.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * AI Entity Configuration
 * 
 * Represents the configuration for an AI-capable entity.
 * Contains all AI processing settings and field configurations.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
public class AIEntityConfig {
    
    private String entityType;
    private List<String> features;
    private boolean autoProcess;
    private boolean enableSearch;
    private boolean enableRecommendations;
    private boolean autoEmbedding;
    private boolean indexable;
    
    private List<AISearchableField> searchableFields;
    private List<AIEmbeddableField> embeddableFields;
    private List<AIMetadataField> metadataFields;
    private Map<String, AICrudOperation> crudOperations;
}
