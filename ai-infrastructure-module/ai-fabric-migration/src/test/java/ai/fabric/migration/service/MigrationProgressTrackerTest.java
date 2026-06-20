package ai.fabric.migration.service;

import ai.fabric.migration.domain.MigrationJob;
import ai.fabric.migration.domain.MigrationProgress;
import ai.fabric.migration.domain.MigrationStatus;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationProgressTrackerTest {

    private final Clock clock = Clock.fixed(Instant.parse("2024-01-01T00:00:10Z"), ZoneId.of("UTC"));
    private final MigrationProgressTracker tracker = new MigrationProgressTracker(clock);

    @Test
    void estimatesRemainingTimeFromProcessedRate() {
        MigrationJob job = baseJob();
        job.setTotalEntities(100L);
        job.setProcessedEntities(25L);
        job.setStartedAt(LocalDateTime.ofInstant(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC")));

        MigrationProgress progress = tracker.toProgress(job);

        assertThat(progress.getPercentComplete()).isEqualTo(25.0);
        assertThat(progress.getEstimatedTimeRemaining()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void omitsEtaWhenProgressDataIsIncomplete() {
        MigrationJob job = baseJob();
        job.setTotalEntities(null);
        job.setProcessedEntities(5L);

        MigrationProgress progress = tracker.toProgress(job);

        assertThat(progress.getTotal()).isZero();
        assertThat(progress.getProcessed()).isEqualTo(5L);
        assertThat(progress.getPercentComplete()).isEqualTo(100.0);
        assertThat(progress.getEstimatedTimeRemaining()).isNull();
    }

    @Test
    void completedJobsReportFullProgressEvenWhenRowsWereSkipped() {
        MigrationJob job = baseJob();
        job.setStatus(MigrationStatus.COMPLETED);
        job.setTotalEntities(10L);
        job.setProcessedEntities(2L);
        job.setFailedEntities(1L);

        MigrationProgress progress = tracker.toProgress(job);

        assertThat(progress.getPercentComplete()).isEqualTo(100.0);
        assertThat(progress.getEstimatedTimeRemaining()).isEqualTo(Duration.ZERO);
    }

    private MigrationJob baseJob() {
        return MigrationJob.builder()
            .id("mig-test")
            .entityType("demo")
            .status(MigrationStatus.RUNNING)
            .totalEntities(0L)
            .processedEntities(0L)
            .failedEntities(0L)
            .currentPage(0)
            .batchSize(10)
            .reindexExisting(false)
            .startedAt(LocalDateTime.ofInstant(Instant.parse("2024-01-01T00:00:00Z"), ZoneId.of("UTC")))
            .build();
    }
}
