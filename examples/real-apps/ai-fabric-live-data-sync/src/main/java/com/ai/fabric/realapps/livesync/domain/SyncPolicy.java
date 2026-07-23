package com.ai.fabric.realapps.livesync.domain;

import ai.fabric.annotation.AICapable;
import ai.fabric.annotation.AIContext;
import ai.fabric.annotation.AISearchable;
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
    features = {"embedding", "search", "rag"},
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
    @AIContext(contextKey = "entityId", dataType = "id", priority = 10, required = true)
    private String id;

    @Column(name = "workspace_id", nullable = false, length = 80)
    @AIContext(contextKey = "workspaceId", dataType = "id", priority = 10, required = true)
    private String workspaceId;

    @Column(name = "record_key", nullable = false, length = 80)
    @AIContext(contextKey = "recordKey", dataType = "id", priority = 9, required = true)
    private String recordKey;

    @Column(nullable = false, length = 180)
    @AISearchable(weight = 2.5, preprocessing = "normalize", required = true, tags = {"primary", "policy"})
    @AIContext(contextKey = "title", description = "Human-readable policy title", priority = 9)
    private String title;

    @Column(nullable = false, length = 1600)
    @AISearchable(weight = 2.0, preprocessing = "clean", maxLength = 1600, required = true, tags = {"guidance"})
    private String guidance;

    @Column(nullable = false, length = 120)
    @AIContext(contextKey = "audience", dataType = "string", description = "People covered by the policy", priority = 6)
    private String audience;

    @Column(nullable = false, length = 32)
    @AIContext(contextKey = "status", dataType = "enum", description = "Policy lifecycle status", priority = 8)
    private String status;

    @Column(nullable = false)
    @AIContext(contextKey = "effectiveDate", dataType = "date", format = "yyyy-MM-dd", priority = 7)
    private LocalDate effectiveDate;

    @Column(nullable = false)
    @AIContext(contextKey = "revision", dataType = "number", priority = 10, required = true)
    private Integer revision;

    @Column(nullable = false)
    @AIContext(contextKey = "updatedAt", dataType = "date", format = "yyyy-MM-dd'T'HH:mm:ss", priority = 5)
    private LocalDateTime updatedAt;
}
