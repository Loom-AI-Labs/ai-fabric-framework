package com.ai.infrastructure.web.migration.dto;

import com.ai.infrastructure.migration.domain.MigrationJob;
import com.ai.infrastructure.migration.domain.MigrationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationJobDTO {
    String id;
    String entityType;
    MigrationStatus status;
    Long totalEntities;
    Long processedEntities;
    Long failedEntities;
    Integer batchSize;
    Integer rateLimit;
    Boolean reindexExisting;
    LocalDateTime startedAt;
    LocalDateTime completedAt;
    LocalDateTime lastUpdatedAt;
    String createdBy;

    public static MigrationJobDTO from(MigrationJob job) {
        return MigrationJobDTO.builder()
            .id(job.getId())
            .entityType(job.getEntityType())
            .status(job.getStatus())
            .totalEntities(job.getTotalEntities())
            .processedEntities(job.getProcessedEntities())
            .failedEntities(job.getFailedEntities())
            .batchSize(job.getBatchSize())
            .rateLimit(job.getRateLimit())
            .reindexExisting(job.getReindexExisting())
            .startedAt(job.getStartedAt())
            .completedAt(job.getCompletedAt())
            .lastUpdatedAt(job.getLastUpdatedAt())
            .createdBy(job.getCreatedBy())
            .build();
    }
}
