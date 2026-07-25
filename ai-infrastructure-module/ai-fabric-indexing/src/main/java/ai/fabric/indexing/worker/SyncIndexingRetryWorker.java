package ai.fabric.indexing.worker;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Retries synchronous work that failed after the source transaction committed.
 */
public class SyncIndexingRetryWorker {

    private final IndexingWorkerRunner runner;
    private final AIIndexingProperties properties;

    public SyncIndexingRetryWorker(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        AIIndexingProperties properties
    ) {
        this.runner = new IndexingWorkerRunner(queueService, workProcessor);
        this.properties = properties;
    }

    @Scheduled(
        fixedDelayString =
            "#{T(java.time.Duration).parse('${ai.indexing.sync-retry-worker.fixed-delay:PT2S}').toMillis()}"
    )
    public void run() {
        if (!properties.isEnabled()
            || !properties.getSyncRetryWorker().isEnabled()) {
            return;
        }
        runner.run(
            IndexingStrategy.SYNC,
            properties.getSyncRetryWorker().getBatchSize(),
            "Sync retry"
        );
    }
}
