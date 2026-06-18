package ai.fabric.indexing.worker;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.springframework.scheduling.annotation.Scheduled;

public class BatchIndexingWorker {

    private final IndexingWorkerRunner runner;
    private final AIIndexingProperties properties;

    public BatchIndexingWorker(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        AIIndexingProperties properties
    ) {
        this.runner = new IndexingWorkerRunner(queueService, workProcessor);
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${ai.indexing.batch-worker.fixed-delay:PT15S}').toMillis()}")
    public void run() {
        if (!properties.isEnabled() || !properties.getBatchWorker().isEnabled()) {
            return;
        }

        runner.run(IndexingStrategy.BATCH, properties.getBatchWorker().getBatchSize(), "Batch");
    }
}
