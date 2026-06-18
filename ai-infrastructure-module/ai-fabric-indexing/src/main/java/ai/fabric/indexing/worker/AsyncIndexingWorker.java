package ai.fabric.indexing.worker;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.springframework.scheduling.annotation.Scheduled;

public class AsyncIndexingWorker {

    private final IndexingWorkerRunner runner;
    private final AIIndexingProperties properties;

    public AsyncIndexingWorker(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        AIIndexingProperties properties
    ) {
        this.runner = new IndexingWorkerRunner(queueService, workProcessor);
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${ai.indexing.async-worker.fixed-delay:PT1S}').toMillis()}")
    public void run() {
        if (!properties.isEnabled() || !properties.getAsyncWorker().isEnabled()) {
            return;
        }

        runner.run(IndexingStrategy.ASYNC, properties.getAsyncWorker().getBatchSize(), "Async");
    }
}
