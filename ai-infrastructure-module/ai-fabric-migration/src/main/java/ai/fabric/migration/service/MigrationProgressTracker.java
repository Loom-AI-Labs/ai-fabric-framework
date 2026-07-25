package ai.fabric.migration.service;

import ai.fabric.migration.domain.MigrationJob;
import ai.fabric.migration.domain.MigrationProgress;
import ai.fabric.migration.domain.MigrationStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

public class MigrationProgressTracker {

    private final Clock clock;

    public MigrationProgressTracker(Clock clock) {
        this.clock = clock;
    }

    public MigrationProgress toProgress(MigrationJob job) {
        double percent = calculatePercent(job);
        Duration eta = calculateEta(job).orElse(null);

        return MigrationProgress.builder()
            .jobId(job.getId())
            .status(job.getStatus())
            .total(toCount(job.getTotalEntities()))
            .processed(toCount(job.getProcessedEntities()))
            .failed(toCount(job.getFailedEntities()))
            .projectionFailures(toCount(job.getProjectionFailures()))
            .enqueueFailures(toCount(job.getEnqueueFailures()))
            .percentComplete(percent)
            .estimatedTimeRemaining(eta)
            .build();
    }

    private long toCount(Long value) {
        return value == null ? 0L : value;
    }

    private double calculatePercent(MigrationJob job) {
        if (job.getStatus() == MigrationStatus.COMPLETED) {
            return 100.0;
        }
        Long total = job.getTotalEntities();
        Long processed = job.getProcessedEntities();
        if (total == null || total <= 0) {
            return 100.0;
        }
        if (processed == null || processed <= 0) {
            return 0.0;
        }
        return Math.min(100.0, (processed * 100.0) / total);
    }

    private Optional<Duration> calculateEta(MigrationJob job) {
        if (job.getStatus() == MigrationStatus.COMPLETED) {
            return Optional.of(Duration.ZERO);
        }
        Long total = job.getTotalEntities();
        Long processed = job.getProcessedEntities();
        if (job.getStartedAt() == null
            || total == null
            || total <= 0
            || processed == null
            || processed <= 0) {
            return Optional.empty();
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Duration elapsed = Duration.between(job.getStartedAt(), now);
        long remaining = total - processed;
        if (remaining <= 0) {
            return Optional.of(Duration.ZERO);
        }
        if (elapsed.isNegative()) {
            return Optional.empty();
        }
        long avgPerEntityMillis = elapsed.toMillis() / Math.max(1, processed);
        return Optional.of(Duration.ofMillis(avgPerEntityMillis * remaining));
    }
}
