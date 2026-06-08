package com.ai.infrastructure.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Vector Record DTO
 * 
 * Represents a vector record in the vector database with all associated metadata.
 * This class is used for batch operations and vector management.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorRecord {
    
    /**
     * Unique vector ID assigned by the vector database
     */
    private String vectorId;
    
    /**
     * Entity type (e.g., "Product", "Customer", "Document")
     */
    private String entityType;
    
    /**
     * Entity ID from the original entity
     */
    private String entityId;
    
    /**
     * Searchable text content
     */
    private String content;
    
    /**
     * Vector embedding
     */
    private List<Double> embedding;
    
    /**
     * Additional metadata
     */
    private Map<String, Object> metadata;
    
    /**
     * AI analysis result
     */
    private String aiAnalysis;
    
    /**
     * Creation timestamp
     */
    private LocalDateTime createdAt;
    
    /**
     * Last update timestamp
     */
    private LocalDateTime updatedAt;
    
    /**
     * Vector database specific metadata
     */
    private Map<String, Object> vectorMetadata;
    
    /**
     * Similarity score (used in search results)
     */
    private Double similarityScore;
    
    /**
     * Whether this vector is active
     */
    @Builder.Default
    private Boolean active = true;
    
    /**
     * Version for optimistic locking
     */
    @Builder.Default
    private Integer version = 1;
}