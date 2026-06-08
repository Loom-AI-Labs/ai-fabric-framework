package com.ai.infrastructure.migration.domain;

import com.ai.infrastructure.migration.domain.converter.MigrationFiltersConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "ai_migration_jobs", indexes = {
    @Index(name = "idx_mig_status", columnList = "status"),
    @Index(name = "idx_mig_entity_type", columnList = "entity_type"),
    @Index(name = "idx_mig_started_at", columnList = "started_at")
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MigrationJob {

    @Id
    @Column(length = 255, nullable = false)
    private String id;

    @Column(name = "entity_type", nullable = false, length = 255)
    private String entityType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private MigrationStatus status;

    @Column(name = "total_entities", nullable = false)
    private Long totalEntities;

    @Column(name = "processed_entities", nullable = false)
    private Long processedEntities;

    @Column(name = "failed_entities", nullable = false)
    private Long failedEntities;

    @Column(name = "current_page", nullable = false)
    private Integer currentPage;

    @Column(name = "batch_size", nullable = false)
    private Integer batchSize;

    @Column(name = "rate_limit")
    private Integer rateLimit;

    @Column(name = "reindex_existing", nullable = false)
    private Boolean reindexExisting;

    @Column(name = "filter_config", columnDefinition = "TEXT")
    @Convert(converter = MigrationFiltersConverter.class)
    private MigrationFilters filters;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    public boolean isPaused() {
        return status == MigrationStatus.PAUSED;
    }

    public boolean isCancelled() {
        return status == MigrationStatus.CANCELLED;
    }

    public boolean isComplete() {
        return status == MigrationStatus.COMPLETED
            || status == MigrationStatus.FAILED
            || status == MigrationStatus.CANCELLED;
    }
}
