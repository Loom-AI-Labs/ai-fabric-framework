package ai.fabric.indexing.worker;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Shared lease/process/ack loop for scheduled indexing workers.
 */
@Slf4j
final class IndexingWorkerRunner {

    private final IndexingQueueService queueService;
    private final IndexingWorkProcessor workProcessor;

    IndexingWorkerRunner(IndexingQueueService queueService, IndexingWorkProcessor workProcessor) {
        this.queueService = queueService;
        this.workProcessor = workProcessor;
    }

    void run(IndexingStrategy strategy, int configuredBatchSize, String workerName) {
        int batchSize = Math.max(1, configuredBatchSize);
        List<IndexingQueueEntry> entries = queueService.lease(strategy, batchSize);
        if (entries.isEmpty()) {
            return;
        }

        for (IndexingQueueEntry entry : entries) {
            try {
                workProcessor.process(entry);
            } catch (Exception ex) {
                log.error("{} indexing failed for entry {}", workerName, entry.getId(), ex);
                markFailure(entry, ex, workerName);
                continue;
            }

            try {
                queueService.markCompleted(entry);
            } catch (Exception ex) {
                log.error("{} indexing completed but acknowledgement failed for entry {}", workerName, entry.getId(), ex);
            }
        }
    }

    private void markFailure(IndexingQueueEntry entry, Exception cause, String workerName) {
        try {
            queueService.markFailure(entry, safeMessage(cause));
        } catch (Exception ex) {
            log.error("{} indexing failed and failure acknowledgement also failed for entry {}", workerName, entry.getId(), ex);
        }
    }

    private static String safeMessage(Exception ex) {
        return ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
    }
}
