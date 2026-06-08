package com.ai.infrastructure.migration.service;

import com.ai.infrastructure.migration.domain.MigrationJob;
import com.ai.infrastructure.migration.domain.MigrationProgress;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

public class MigrationProgressTracker {

    private final Clock clock;

    public MigrationProgressTracker(Clock clock) {
        this.clock = clock;
    }

    public MigrationProgress toProgress(MigrationJob job) {
        double percent = calculatePercent(job);
        Duration eta = calculateEta(job);

        return MigrationProgress.builder()
            .jobId(job.getId())
            .status(job.getStatus())
            .total(job.getTotalEntities())
            .processed(job.getProcessedEntities())
            .failed(job.getFailedEntities())
            .percentComplete(percent)
            .estimatedTimeRemaining(eta)
            .build();
    }

    private double calculatePercent(MigrationJob job) {
        if (job.getTotalEntities() == null || job.getTotalEntities() == 0) {
            return 100.0;
        }
        return Math.min(100.0, (job.getProcessedEntities() * 100.0) / job.getTotalEntities());
    }

    private Duration calculateEta(MigrationJob job) {
        if (job.getStartedAt() == null || job.getProcessedEntities() == null || job.getProcessedEntities() == 0) {
            return null;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Duration elapsed = Duration.between(job.getStartedAt(), now);
        long remaining = job.getTotalEntities() - job.getProcessedEntities();
        if (remaining <= 0) {
            return Duration.ZERO;
        }
        long avgPerEntityMillis = elapsed.toMillis() / Math.max(1, job.getProcessedEntities());
        return Duration.ofMillis(avgPerEntityMillis * remaining);
    }
}
