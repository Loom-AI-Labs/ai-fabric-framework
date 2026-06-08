package com.ai.infrastructure.web.migration.dto;

import com.ai.infrastructure.migration.domain.MigrationProgress;
import com.ai.infrastructure.migration.domain.MigrationStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MigrationProgressDTO {
    String jobId;
    MigrationStatus status;
    long total;
    long processed;
    long failed;
    double percentComplete;
    Duration estimatedTimeRemaining;

    public static MigrationProgressDTO from(MigrationProgress progress) {
        return MigrationProgressDTO.builder()
            .jobId(progress.getJobId())
            .status(progress.getStatus())
            .total(progress.getTotal())
            .processed(progress.getProcessed())
            .failed(progress.getFailed())
            .percentComplete(progress.getPercentComplete())
            .estimatedTimeRemaining(progress.getEstimatedTimeRemaining())
            .build();
    }
}
