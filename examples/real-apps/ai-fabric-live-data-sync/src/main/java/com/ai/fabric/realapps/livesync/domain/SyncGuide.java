package com.ai.fabric.realapps.livesync.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AIIdentity;
import ai.fabric.annotation.AISearchable;
import ai.fabric.indexing.api.AIContextDataType;
import ai.fabric.indexing.api.AISearchPreprocessing;
import ai.fabric.indexing.api.IndexingStrategy;
import com.ai.fabric.realapps.livesync.repository.SyncGuideRepository;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
    name = "sync_guide",
    uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "record_key"})
)
@AICapable(
    entityType = SyncGuide.ENTITY_TYPE,
    indexingStrategy = IndexingStrategy.SYNC,
    onCreateStrategy = IndexingStrategy.SYNC,
    onUpdateStrategy = IndexingStrategy.SYNC,
    onDeleteStrategy = IndexingStrategy.AUTO,
    migrationRepository = SyncGuideRepository.class
)
public class SyncGuide {

    public static final String ENTITY_TYPE = "sync-guide";

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
    @AIContext(key = "title", description = "Troubleshooting guide title", priority = 90)
    private String title;

    @Column(nullable = false, length = 800)
    @AISearchable(priority = 70, preprocessing = AISearchPreprocessing.CLEAN, maxLength = 800)
    private String symptoms;

    @Column(nullable = false, length = 1600)
    @AISearchable(priority = 90, preprocessing = AISearchPreprocessing.CLEAN, maxLength = 1600, required = true)
    private String resolution;

    @Column(nullable = false, length = 100)
    @AIContext(key = "productArea", dataType = AIContextDataType.STRING, description = "Product area covered by the guide", priority = 70)
    private String productArea;

    @Column(nullable = false, length = 32)
    @AIContext(key = "severity", dataType = AIContextDataType.STRING, description = "Expected incident severity", priority = 60)
    private String severity;

    @Column(nullable = false)
    @AIContext(key = "revision", dataType = AIContextDataType.NUMBER, priority = 100, required = true)
    private Integer revision;

    @Column(nullable = false)
    @AIContext(key = "updatedAt", dataType = AIContextDataType.DATE, format = "yyyy-MM-dd'T'HH:mm:ss", priority = 50)
    private LocalDateTime updatedAt;
}
