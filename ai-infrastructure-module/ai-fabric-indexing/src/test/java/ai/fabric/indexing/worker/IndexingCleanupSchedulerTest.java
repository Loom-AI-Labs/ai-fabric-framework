package ai.fabric.indexing.worker;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class IndexingCleanupSchedulerTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-06-18T10:15:30Z"),
        ZoneOffset.UTC
    );

    @Test
    void reclaimStuckEntriesDelegatesWhenCleanupIsEnabled() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        IndexingCleanupScheduler scheduler = new IndexingCleanupScheduler(queueService, properties, FIXED_CLOCK);

        scheduler.reclaimStuckEntries();

        verify(queueService).resetStuckEntries();
    }

    @Test
    void cleanupJobsDoNothingWhenModuleIsDisabled() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.setEnabled(false);
        IndexingCleanupScheduler scheduler = new IndexingCleanupScheduler(queueService, properties, FIXED_CLOCK);

        scheduler.reclaimStuckEntries();
        scheduler.purgeOldEntries();

        verifyNoInteractions(queueService);
    }

    @Test
    void cleanupJobsDoNothingWhenCleanupIsDisabled() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getCleanup().setEnabled(false);
        IndexingCleanupScheduler scheduler = new IndexingCleanupScheduler(queueService, properties, FIXED_CLOCK);

        scheduler.reclaimStuckEntries();
        scheduler.purgeOldEntries();

        verifyNoInteractions(queueService);
    }

    @Test
    void purgeOldEntriesUsesConfiguredRetentionThresholds() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getCleanup().setCompletedRetention(Duration.ofDays(2));
        properties.getCleanup().setDeadLetterRetention(Duration.ofDays(9));
        IndexingCleanupScheduler scheduler = new IndexingCleanupScheduler(queueService, properties, FIXED_CLOCK);

        scheduler.purgeOldEntries();

        verify(queueService).purgeCompletedOlderThan(LocalDateTime.of(2026, 6, 16, 10, 15, 30));
        verify(queueService).purgeDeadLettersOlderThan(LocalDateTime.of(2026, 6, 9, 10, 15, 30));
        verify(queueService, never()).resetStuckEntries();
    }
}
