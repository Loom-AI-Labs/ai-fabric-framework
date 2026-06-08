package com.ai.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity capturing structured intent executions.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "intent_history", indexes = {
    @Index(name = "idx_intent_history_user", columnList = "user_id"),
    @Index(name = "idx_intent_history_created", columnList = "created_at"),
    @Index(name = "idx_intent_history_expires", columnList = "expires_at")
})
public class IntentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "user_id", nullable = false, length = 255)
    private String userId;

    @Column(name = "session_id", length = 255)
    private String sessionId;

    @Column(name = "query_redacted", columnDefinition = "TEXT")
    private String redactedQuery;

    @Column(name = "query_encrypted", columnDefinition = "TEXT")
    private String encryptedQuery;

    @Column(name = "intents_json", columnDefinition = "TEXT")
    private String intentsJson;

    @Column(name = "result_json", columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "metadata_json", columnDefinition = "TEXT")
    private String metadataJson;

    @Column(name = "execution_status", length = 50)
    private String executionStatus;

    @Column(name = "success")
    private Boolean success;

    @Column(name = "has_sensitive_data")
    private Boolean hasSensitiveData;

    @Column(name = "sensitive_data_types", length = 512)
    private String sensitiveDataTypes;

    @Column(name = "intent_count")
    private Integer intentCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
