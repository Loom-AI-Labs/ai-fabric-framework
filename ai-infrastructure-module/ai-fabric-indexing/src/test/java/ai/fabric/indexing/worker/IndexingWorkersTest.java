package ai.fabric.indexing.worker;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.queue.IndexingQueueService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IndexingWorkersTest {

    @Test
    void asyncWorkerLeasesAsyncEntriesWithConfiguredBatchSize() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        IndexingWorkProcessor workProcessor = mock(IndexingWorkProcessor.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getAsyncWorker().setBatchSize(12);
        AsyncIndexingWorker worker = new AsyncIndexingWorker(queueService, workProcessor, properties);

        when(queueService.lease(IndexingStrategy.ASYNC, 12)).thenReturn(List.of());

        worker.run();

        verify(queueService).lease(IndexingStrategy.ASYNC, 12);
        verifyNoInteractions(workProcessor);
    }

    @Test
    void batchWorkerLeasesBatchEntriesWithConfiguredBatchSize() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        IndexingWorkProcessor workProcessor = mock(IndexingWorkProcessor.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getBatchWorker().setBatchSize(250);
        BatchIndexingWorker worker = new BatchIndexingWorker(queueService, workProcessor, properties);

        when(queueService.lease(IndexingStrategy.BATCH, 250)).thenReturn(List.of());

        worker.run();

        verify(queueService).lease(IndexingStrategy.BATCH, 250);
        verifyNoInteractions(workProcessor);
    }

    @Test
    void workersDoNothingWhenIndexingModuleIsDisabled() {
        IndexingQueueService queueService = mock(IndexingQueueService.class);
        IndexingWorkProcessor workProcessor = mock(IndexingWorkProcessor.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.setEnabled(false);

        new AsyncIndexingWorker(queueService, workProcessor, properties).run();
        new BatchIndexingWorker(queueService, workProcessor, properties).run();

        verifyNoInteractions(queueService, workProcessor);
    }
}
