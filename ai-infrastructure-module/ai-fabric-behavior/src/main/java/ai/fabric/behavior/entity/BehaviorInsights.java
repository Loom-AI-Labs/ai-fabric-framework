package ai.fabric.behavior.entity;

import ai.fabric.annotation.AICapable;
import ai.fabric.behavior.converter.JsonbListConverter;
import ai.fabric.behavior.converter.JsonbMapConverter;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.SentimentLabel;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(
    name = "ai_behavior_insights",
    indexes = {
        @Index(name = "idx_insights_user", columnList = "user_id"),
        @Index(name = "idx_insights_segment", columnList = "segment"),
        @Index(name = "idx_insights_sentiment", columnList = "sentiment_label"),
        @Index(name = "idx_insights_churn", columnList = "churn_risk"),
        @Index(name = "idx_insights_trend", columnList = "trend")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@AICapable(
    entityType = "behavior-insight",
    autoEmbedding = true,
    indexable = true
)
public class BehaviorInsights {
    
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;
    
    @Column(name = "user_id", nullable = false, unique = true, length = 255)
    private String userId;
    
    // Core AI insights
    @Column(name = "segment", length = 100)
    private String segment;
    
    @Column(name = "patterns", columnDefinition = "jsonb")
    @Convert(converter = JsonbListConverter.class)
    private List<String> patterns;
    
    @Column(name = "recommendations", columnDefinition = "jsonb")
    @Convert(converter = JsonbListConverter.class)
    private List<String> recommendations;
    
    @Column(name = "insights", columnDefinition = "jsonb")
    @Convert(converter = JsonbMapConverter.class)
    private Map<String, Object> insights;
    
    // Sentiment analysis
    @Column(name = "sentiment_score")
    private Double sentimentScore;
    
    @Column(name = "sentiment_label", length = 50)
    @Enumerated(EnumType.STRING)
    private SentimentLabel sentimentLabel;
    
    // Churn risk analysis
    @Column(name = "churn_risk")
    private Double churnRisk;
    
    @Column(name = "churn_reason", columnDefinition = "TEXT")
    private String churnReason;
    
    // Trend tracking (deltas)
    @Column(name = "previous_sentiment_score")
    private Double previousSentimentScore;
    
    @Column(name = "previous_churn_risk")
    private Double previousChurnRisk;
    
    @Column(name = "trend", length = 50)
    @Enumerated(EnumType.STRING)
    private BehaviorTrend trend;
    
    // Metadata
    @Column(name = "analyzed_at", nullable = false)
    private LocalDateTime analyzedAt;
    
    @Column(name = "confidence")
    private Double confidence;
    
    @Column(name = "ai_model_used", length = 100)
    private String aiModelUsed;
    
    @Column(name = "model_prompt_version", length = 20)
    private String modelPromptVersion;
    
    @Column(name = "processing_time_ms")
    private Long processingTimeMs;
    
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
    
    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
    
    @Transient
    public Double getSentimentDelta() {
        if (sentimentScore == null || previousSentimentScore == null) {
            return null;
        }
        return sentimentScore - previousSentimentScore;
    }
    
    @Transient
    public Double getChurnDelta() {
        if (churnRisk == null || previousChurnRisk == null) {
            return null;
        }
        return churnRisk - previousChurnRisk;
    }
    
    @Transient
    public boolean isSentimentImproving() {
        Double delta = getSentimentDelta();
        return delta != null && delta > 0.2;
    }
    
    @Transient
    public boolean isChurnRiskIncreasing() {
        Double delta = getChurnDelta();
        return delta != null && delta > 0.2;
    }
    
    @Transient
    public boolean requiresImmediateAction() {
        return (trend != null && trend.requiresIntervention())
            || (churnRisk != null && churnRisk > 0.8)
            || (getChurnDelta() != null && getChurnDelta() > 0.4);
    }
    
    /**
     * Framework uses this to build searchable content for vector indexing.
     */
    public String getSearchableContent() {
        return String.format(
            "Segment: %s | Sentiment: %s | Churn: %.2f | Trend: %s | Confidence: %.2f | Patterns: %s | Recs: %s",
            segment != null ? segment : "Unknown",
            sentimentLabel != null ? sentimentLabel.name() : "UNKNOWN",
            churnRisk != null ? churnRisk : 0.0,
            trend != null ? trend.name() : "UNKNOWN",
            confidence != null ? confidence : 0.0,
            patterns != null ? String.join(", ", patterns) : "None",
            recommendations != null ? String.join(", ", recommendations) : "None"
        );
    }
}
