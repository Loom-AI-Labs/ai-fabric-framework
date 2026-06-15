package ai.fabric.indexing.worker;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

@Slf4j
public class BatchIndexingWorker {

    private final IndexingQueueService queueService;
    private final IndexingWorkProcessor workProcessor;
    private final AIIndexingProperties properties;

    public BatchIndexingWorker(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        AIIndexingProperties properties
    ) {
        this.queueService = queueService;
        this.workProcessor = workProcessor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "#{T(java.time.Duration).parse('${ai.indexing.batch-worker.fixed-delay:PT15S}').toMillis()}")
    public void run() {
        if (!properties.isEnabled() || !properties.getBatchWorker().isEnabled()) {
            return;
        }

        int batchSize = Math.max(1, properties.getBatchWorker().getBatchSize());
        List<IndexingQueueEntry> entries = queueService.lease(IndexingStrategy.BATCH, batchSize);
        if (entries.isEmpty()) {
            return;
        }

        for (IndexingQueueEntry entry : entries) {
            try {
                workProcessor.process(entry);
                queueService.markCompleted(entry);
            } catch (Exception ex) {
                log.error("Batch indexing failed for entry {}", entry.getId(), ex);
                queueService.markFailure(entry, ex.getMessage());
            }
        }
    }
}
