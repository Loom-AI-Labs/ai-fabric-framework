package com.ai.fabric.realapps.livesync.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.api.IndexingStrategy;
import com.ai.fabric.realapps.livesync.repository.SyncPolicyRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "sync_policy",
    uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "record_key"})
)
@AICapable(
    entityType = SyncPolicy.ENTITY_TYPE,
    indexingStrategy = IndexingStrategy.SYNC,
    onCreateStrategy = IndexingStrategy.SYNC,
    onUpdateStrategy = IndexingStrategy.AUTO,
    onDeleteStrategy = IndexingStrategy.SYNC,
    migrationRepository = SyncPolicyRepository.class
)
public class SyncPolicy {

    public static final String ENTITY_TYPE = "sync-policy";

    @Id
    @Column(nullable = false, length = 160)
    @AIIdentity
    @AIContext(key = "entityId", dataType = AIContextDataType.ID, priority = 100, required = true)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 80)
    @AIContext(key = "workspaceId", dataType = AIContextDataType.ID, priority = 100, required = true)
    private String workspaceId;

    @Column(name = "record_key", nullable = false, length = 80)
    @AIContext(key = "recordKey", dataType = AIContextDataType.ID, priority = 90, required = true)
    private String recordKey;

    @Column(nullable = false, length = 180)
    @AISearchable(priority = 100, preprocessing = AISearchPreprocessing.NORMALIZE, required = true)
    @AIContext(key = "title", description = "Human-readable policy title", priority = 90)
    private String title;

    @Column(nullable = false, length = 1600)
    @AISearchable(priority = 90, preprocessing = AISearchPreprocessing.CLEAN, maxLength = 1600, required = true)
    private String guidance;

    @Column(nullable = false, length = 120)
    @AIContext(key = "audience", dataType = AIContextDataType.STRING, description = "People covered by the policy", priority = 60)
    private String audience;

    @Column(nullable = false, length = 32)
    @AIContext(key = "status", dataType = AIContextDataType.STRING, description = "Policy lifecycle status", priority = 80)
    private String status;

    @Column(nullable = false)
    @AIContext(key = "effectiveDate", dataType = AIContextDataType.DATE, format = "yyyy-MM-dd", priority = 70)
    private LocalDate effectiveDate;

    @Column(nullable = false)
    @AIContext(
        key = "version",
        dataType = AIContextDataType.NUMBER,
        description = "Monotonic source revision used for stale-work protection",
        priority = 100,
        required = true
    )
    private Integer revision;

    @Column(nullable = false)
    @AIContext(key = "updatedAt", dataType = AIContextDataType.DATE, format = "yyyy-MM-dd'T'HH:mm:ss", priority = 50)
    private LocalDateTime updatedAt;
}
