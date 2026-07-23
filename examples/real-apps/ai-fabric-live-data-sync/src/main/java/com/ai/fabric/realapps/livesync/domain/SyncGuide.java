package com.ai.fabric.realapps.livesync.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AISearchable;
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
    features = {"embedding", "search", "rag"},
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
    @AIContext(contextKey = "entityId", dataType = "id", priority = 10, required = true)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 80)
    @AIContext(contextKey = "workspaceId", dataType = "id", priority = 10, required = true)
    private String workspaceId;

    @Column(name = "record_key", nullable = false, length = 80)
    @AIContext(contextKey = "recordKey", dataType = "id", priority = 9, required = true)
    private String recordKey;

    @Column(nullable = false, length = 180)
    @AISearchable(weight = 2.3, preprocessing = "normalize", required = true, tags = {"primary", "guide"})
    @AIContext(contextKey = "title", description = "Troubleshooting guide title", priority = 9)
    private String title;

    @Column(nullable = false, length = 800)
    @AISearchable(weight = 1.6, preprocessing = "clean", maxLength = 800, tags = {"symptoms"})
    private String symptoms;

    @Column(nullable = false, length = 1600)
    @AISearchable(weight = 2.0, preprocessing = "clean", maxLength = 1600, required = true, tags = {"resolution"})
    private String resolution;

    @Column(nullable = false, length = 100)
    @AIContext(contextKey = "productArea", dataType = "string", description = "Product area covered by the guide", priority = 7)
    private String productArea;

    @Column(nullable = false, length = 32)
    @AIContext(contextKey = "severity", dataType = "enum", description = "Expected incident severity", priority = 6)
    private String severity;

    @Column(nullable = false)
    @AIContext(contextKey = "revision", dataType = "number", priority = 10, required = true)
    private Integer revision;

    @Column(nullable = false)
    @AIContext(contextKey = "updatedAt", dataType = "date", format = "yyyy-MM-dd'T'HH:mm:ss", priority = 5)
    private LocalDateTime updatedAt;
}
