package com.ai.infrastructure.entity;

import com.ai.infrastructure.annotation.AICapable;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * AI Profile Entity
 * 
 * Generic AI profile entity for storing AI-related user profiles and attributes.
 * This entity is designed to be domain-agnostic and can be used across different applications.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Entity
@Table(name = "ai_infrastructure_profiles")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@AICapable(
    entityType = "ai_profile",
    features = {"embedding", "search", "analysis", "behavioral"},
    enableSearch = true,
    enableRecommendations = false,
    autoEmbedding = true,
    indexable = true
)
public class AIInfrastructureProfile {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "preferences", columnDefinition = "json")
    private String preferences;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "interests", columnDefinition = "json")
    private String interests;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "behavior_patterns", columnDefinition = "json")
    private String behaviorPatterns;
    
    @Column(name = "cv_file_url", length = 500)
    private String cvFileUrl;
    
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private AIProfileStatus status;
    
    @Column(name = "confidence_score")
    private Double confidenceScore;
    
    @Column(name = "version")
    private Integer version;
    
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    
    public enum AIProfileStatus {
        ACTIVE,
        INACTIVE,
        PENDING,
        PROCESSING,
        ERROR,
        EXPIRED
    }
    
    // Helper methods for AI operations
    
    /**
     * Get the primary searchable text for AI operations
     */
    public String getSearchableText() {
        StringBuilder text = new StringBuilder();
        if (preferences != null) {
            text.append(preferences).append(" ");
        }
        if (interests != null) {
            text.append(interests).append(" ");
        }
        if (behaviorPatterns != null) {
            text.append(behaviorPatterns).append(" ");
        }
        if (cvFileUrl != null) {
            text.append(cvFileUrl).append(" ");
        }
        return text.toString().trim();
    }
    
    /**
     * Get metadata for AI operations
     */
    public java.util.Map<String, Object> getAIMetadata() {
        java.util.Map<String, Object> metadata = new java.util.HashMap<>();
        metadata.put("id", id.toString());
        metadata.put("userId", userId.toString());
        metadata.put("status", status.toString());
        metadata.put("confidenceScore", confidenceScore != null ? confidenceScore : 0.0);
        metadata.put("version", version != null ? version : 1);
        metadata.put("createdAt", createdAt.toString());
        metadata.put("updatedAt", updatedAt != null ? updatedAt.toString() : "");
        return metadata;
    }
}