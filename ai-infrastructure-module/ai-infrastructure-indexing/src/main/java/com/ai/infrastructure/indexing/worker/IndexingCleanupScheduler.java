package com.ai.infrastructure.indexing.worker;

import com.ai.infrastructure.config.AIIndexingProperties;
import com.ai.infrastructure.indexing.queue.IndexingQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.LocalDateTime;

@Slf4j
public class IndexingCleanupScheduler {

    private final IndexingQueueService queueService;
    private final AIIndexingProperties properties;
    private final Clock clock;

    public IndexingCleanupScheduler(
        IndexingQueueService queueService,
        AIIndexingProperties properties,
        Clock clock
    ) {
        this.queueService = queueService;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${ai.indexing.cleanup.sweep-interval:PT5M}').toMillis()}")
    public void reclaimStuckEntries() {
        if (!properties.isEnabled() || !properties.getCleanup().isEnabled()) {
            return;
        }
        queueService.resetStuckEntries();
    }

    @Scheduled(cron = "0 0 * * * *")
    public void purgeOldEntries() {
        if (!properties.isEnabled() || !properties.getCleanup().isEnabled()) {
            return;
        }

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime completedThreshold = now.minus(properties.getCleanup().getCompletedRetention());
        LocalDateTime deadLetterThreshold = now.minus(properties.getCleanup().getDeadLetterRetention());

        int completed = queueService.purgeCompletedOlderThan(completedThreshold);
        int deadLetters = queueService.purgeDeadLettersOlderThan(deadLetterThreshold);

        if (completed > 0 || deadLetters > 0) {
            log.info("Purged {} completed entries and {} dead-letter entries from indexing queue", completed, deadLetters);
        }
    }
}
