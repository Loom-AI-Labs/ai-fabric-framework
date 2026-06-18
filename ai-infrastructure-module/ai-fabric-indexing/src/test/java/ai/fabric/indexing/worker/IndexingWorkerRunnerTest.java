package ai.fabric.indexing.worker;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IndexingWorkerRunnerTest {

    @Test
    void processesLeasedEntriesAndMarksThemCompleted() throws Exception {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        IndexingWorkProcessor workProcessor = mock(IndexingWorkProcessor.class);
        IndexingWorkerRunner runner = new IndexingWorkerRunner(queueService, workProcessor);
        IndexingQueueEntry entry = entry("entry-1");

        when(queueService.lease(IndexingStrategy.ASYNC, 10)).thenReturn(List.of(entry));

        runner.run(IndexingStrategy.ASYNC, 10, "Async");

        verify(workProcessor).process(entry);
        verify(queueService).markCompleted(entry);
        verify(queueService, never()).markFailure(any(IndexingQueueEntry.class), any());
    }

    @Test
    void marksFailuresForRetryWhenProcessingThrows() throws Exception {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        IndexingWorkProcessor workProcessor = mock(IndexingWorkProcessor.class);
        IndexingWorkerRunner runner = new IndexingWorkerRunner(queueService, workProcessor);
        IndexingQueueEntry entry = entry("entry-2");

        when(queueService.lease(IndexingStrategy.ASYNC, 1)).thenReturn(List.of(entry));
        doThrow(new IllegalStateException("processor down")).when(workProcessor).process(entry);

        runner.run(IndexingStrategy.ASYNC, 1, "Async");

        verify(queueService, never()).markCompleted(entry);
        verify(queueService).markFailure(entry, "processor down");
    }

    @Test
    void normalizesConfiguredBatchSizeToAtLeastOne() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        IndexingWorkProcessor workProcessor = mock(IndexingWorkProcessor.class);
        IndexingWorkerRunner runner = new IndexingWorkerRunner(queueService, workProcessor);

        when(queueService.lease(IndexingStrategy.BATCH, 1)).thenReturn(List.of());

        runner.run(IndexingStrategy.BATCH, 0, "Batch");

        verify(queueService).lease(IndexingStrategy.BATCH, 1);
        verifyNoInteractions(workProcessor);
    }

    private IndexingQueueEntry entry(String id) {
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setId(id);
        return entry;
    }
}
