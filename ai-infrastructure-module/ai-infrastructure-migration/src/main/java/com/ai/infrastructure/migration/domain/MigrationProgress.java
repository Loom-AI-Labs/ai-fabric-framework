package com.ai.infrastructure.migration.domain;

import lombok.Builder;
import lombok.Value;

import java.time.Duration;

@Value
@Builder
public class MigrationProgress {
    String jobId;
    MigrationStatus status;
    long total;
    long processed;
    long failed;
    double percentComplete;
    Duration estimatedTimeRemaining;
}
