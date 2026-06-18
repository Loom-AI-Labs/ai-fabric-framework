package ai.fabric.it.support;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.indexing.worker.IndexingWorkProcessor;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Test-only utility that allows integration tests to deterministically drain the indexing queue
 * instead of waiting for the scheduled workers to pick up work.
 */
@Component
@RequiredArgsConstructor
public class IndexingQueueTestSupport {

    private static final Logger log = LoggerFactory.getLogger(IndexingQueueTestSupport.class);

    private final ObjectProvider<IndexingQueueService> queueServiceProvider;
    private final ObjectProvider<IndexingWorkProcessor> workProcessorProvider;
    private final ObjectProvider<AIIndexingProperties> indexingPropertiesProvider;

    /**
     * Drain both async and batch indexing queues once, best-effort.
     */
    public void drainQueue() {
        IndexingQueueService queueService = queueServiceProvider.getIfAvailable();
        IndexingWorkProcessor workProcessor = workProcessorProvider.getIfAvailable();
        AIIndexingProperties indexingProperties = indexingPropertiesProvider.getIfAvailable();

        if (queueService == null || workProcessor == null) {
            log.debug("Indexing queue components are not available; skipping drain.");
            return;
        }

        int asyncBatchSize = 10;
        int batchBatchSize = 10;
        if (indexingProperties != null) {
            asyncBatchSize = Math.max(1, indexingProperties.getAsyncWorker().getBatchSize());
            batchBatchSize = Math.max(1, indexingProperties.getBatchWorker().getBatchSize());
        }

        drainStrategy(queueService, workProcessor, IndexingStrategy.ASYNC, asyncBatchSize);
        drainStrategy(queueService, workProcessor, IndexingStrategy.BATCH, batchBatchSize);
    }

    private void drainStrategy(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        IndexingStrategy strategy,
        int batchSize
    ) {
        int iteration = 0;
        int maxIterations = 50;
        int idleCycles = 0;
        int maxIdleCycles = 5;
        while (iteration++ < maxIterations && idleCycles < maxIdleCycles) {
            List<IndexingQueueEntry> entries = queueService.lease(strategy, batchSize);
            if (entries.isEmpty()) {
                idleCycles++;
                sleepQuietly(50);
                continue;
            }
            idleCycles = 0;
            for (IndexingQueueEntry entry : entries) {
                try {
                    workProcessor.process(entry);
                    queueService.markCompleted(entry);
                } catch (Exception ex) {
                    log.error("Failed to process indexing entry {} via test drain", entry.getId(), ex);
                    queueService.markFailure(entry, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
                }
            }
        }
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
