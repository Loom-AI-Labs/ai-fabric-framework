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
                IndexingWorkProcessor.WorkResult result = workProcessor.process(entry);
                if (result.status() == ai.fabric.indexing.api.IndexingDispatchStatus.SKIPPED_STALE) {
                    queueService.markSuperseded(entry.getId());
                } else {
                    queueService.markCompleted(entry.getId(), result.resultPayload());
                }
            } catch (Exception ex) {
                log.error(
                    "{} indexing failed for entry {} with code {}",
                    workerName,
                    entry.getId(),
                    safeCode(ex)
                );
                markFailure(entry, ex, workerName);
            }
        }
    }

    private void markFailure(IndexingQueueEntry entry, Exception cause, String workerName) {
        try {
            queueService.markFailure(entry.getId(), safeCode(cause));
        } catch (Exception ex) {
            log.error(
                "{} indexing failed and failure acknowledgement also failed for entry {} with code {}",
                workerName,
                entry.getId(),
                safeCode(ex)
            );
        }
    }

    static String safeCode(Exception exception) {
        if (exception instanceof IndexingExecutionException executionException) {
            return executionException.getErrorCode();
        }
        if (exception instanceof ai.fabric.indexing.queue.IndexingPayloadException payloadException) {
            return payloadException.getErrorCode();
        }
        return "INDEXING_" + exception.getClass().getSimpleName().toUpperCase(java.util.Locale.ROOT);
    }
}
