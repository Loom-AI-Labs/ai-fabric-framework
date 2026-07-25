package ai.fabric.indexing;

import ai.fabric.annotation.NoMigrationRepository;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.EntityIdentityResolver;
import ai.fabric.indexing.api.IndexingDispatchStatus;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.model.AIAnalysisPolicy;
import ai.fabric.indexing.model.AIEntityDescriptor;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.indexing.worker.IndexingExecutionException;
import ai.fabric.indexing.worker.IndexingWorkProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAIEntityIndexingGatewayTest {

    @Test
    void typedAnalysisPolicyCreatesDependentWorkSeparateFromTheUpsert() {
        AIEntityProjectionService projection = mock(AIEntityProjectionService.class);
        AIEntityDescriptorRegistry descriptors = mock(AIEntityDescriptorRegistry.class);
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingWorkProcessor processor = mock(IndexingWorkProcessor.class);
        TestEntity entity = new TestEntity();
        AIIndexDocument primary = upsert();
        IndexingQueueEntry primaryEntry = entry(51L);
        IndexingQueueEntry analysisEntry = entry(52L);
        when(descriptors.resolve(entity)).thenReturn(descriptor(true));
        when(descriptors.getByEntityType("product")).thenReturn(descriptor(true));
        when(projection.project(entity, AIProcessOperation.UPDATE, ""))
            .thenReturn(primary);
        when(queue.enqueue(primary, IndexingStrategy.ASYNC)).thenReturn(primaryEntry);
        when(queue.enqueue(
            any(AIIndexDocument.class),
            eq(IndexingStrategy.ASYNC),
            eq(51L)
        )).thenReturn(analysisEntry);

        var outcome = gateway(projection, descriptors, queue, processor)
            .upsert(entity, AIProcessOperation.UPDATE, IndexingStrategy.ASYNC);

        assertThat(outcome.status()).isEqualTo(IndexingDispatchStatus.QUEUED);
        verify(queue).enqueue(
            org.mockito.ArgumentMatchers.argThat(document ->
                document.workType() == AIIndexWorkType.ANALYZE
                    && document.sourceOperation() == AIProcessOperation.UPDATE
                    && document.entityId().equals("p-1")
            ),
            eq(IndexingStrategy.ASYNC),
            eq(51L)
        );
        verify(processor, never()).process(any());
    }

    @Test
    void disabledAnalysisPolicyCreatesOnlyPrimaryLifecycleWork() {
        AIEntityProjectionService projection = mock(AIEntityProjectionService.class);
        AIEntityDescriptorRegistry descriptors = mock(AIEntityDescriptorRegistry.class);
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingWorkProcessor processor = mock(IndexingWorkProcessor.class);
        TestEntity entity = new TestEntity();
        AIIndexDocument primary = upsert();
        when(descriptors.resolve(entity)).thenReturn(descriptor(false));
        when(descriptors.getByEntityType("product")).thenReturn(descriptor(false));
        when(projection.project(entity, AIProcessOperation.UPDATE, ""))
            .thenReturn(primary);
        when(queue.enqueue(primary, IndexingStrategy.ASYNC)).thenReturn(entry(61L));

        gateway(projection, descriptors, queue, processor)
            .upsert(entity, AIProcessOperation.UPDATE, IndexingStrategy.ASYNC);

        verify(queue, never()).enqueue(
            any(AIIndexDocument.class),
            any(IndexingStrategy.class),
            any(Long.class)
        );
    }

    @Test
    void synchronousTerminalFailureIsReportedAsPermanent() {
        AIEntityProjectionService projection = mock(AIEntityProjectionService.class);
        AIEntityDescriptorRegistry descriptors = mock(AIEntityDescriptorRegistry.class);
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingWorkProcessor processor = mock(IndexingWorkProcessor.class);
        AIIndexDocument document = upsert();
        IndexingQueueEntry work = entry(71L);
        work.setStatus(IndexingStatus.PENDING);
        when(queue.enqueueForSynchronousDispatch(document)).thenReturn(work);
        when(queue.claimSynchronous(71L)).thenReturn(java.util.Optional.of(work));
        when(processor.process(work)).thenThrow(
            new IndexingExecutionException("VECTOR_PROVIDER_REJECTED")
        );
        doAnswer(invocation -> {
            work.setStatus(IndexingStatus.DEAD_LETTER);
            return null;
        }).when(queue).markFailure(71L, "VECTOR_PROVIDER_REJECTED");
        when(queue.requireEntry(71L)).thenReturn(work);

        var outcome = gateway(projection, descriptors, queue, processor)
            .submit(document, IndexingStrategy.SYNC);

        assertThat(outcome.status())
            .isEqualTo(IndexingDispatchStatus.FAILED_PERMANENT);
        verify(queue).markFailure(71L, "VECTOR_PROVIDER_REJECTED");
    }

    @Test
    void synchronousTransientFailureIsReportedAsRetryable() {
        AIEntityProjectionService projection = mock(AIEntityProjectionService.class);
        AIEntityDescriptorRegistry descriptors = mock(AIEntityDescriptorRegistry.class);
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingWorkProcessor processor = mock(IndexingWorkProcessor.class);
        AIIndexDocument document = upsert();
        IndexingQueueEntry work = entry(72L);
        work.setStatus(IndexingStatus.PENDING);
        when(queue.enqueueForSynchronousDispatch(document)).thenReturn(work);
        when(queue.claimSynchronous(72L)).thenReturn(java.util.Optional.of(work));
        when(processor.process(work)).thenThrow(
            new IndexingExecutionException("VECTOR_PROVIDER_UNAVAILABLE")
        );
        doAnswer(invocation -> {
            work.setStatus(IndexingStatus.PENDING);
            return null;
        }).when(queue).markFailure(72L, "VECTOR_PROVIDER_UNAVAILABLE");
        when(queue.requireEntry(72L)).thenReturn(work);

        var outcome = gateway(projection, descriptors, queue, processor)
            .submit(document, IndexingStrategy.SYNC);

        assertThat(outcome.status())
            .isEqualTo(IndexingDispatchStatus.FAILED_RETRYABLE);
        verify(queue).markFailure(72L, "VECTOR_PROVIDER_UNAVAILABLE");
    }

    @Test
    void synchronousAnalysisRemainsWorkerOwnedUntilItsPrimaryCompletes() {
        AIEntityProjectionService projection = mock(AIEntityProjectionService.class);
        AIEntityDescriptorRegistry descriptors = mock(AIEntityDescriptorRegistry.class);
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingWorkProcessor processor = mock(IndexingWorkProcessor.class);
        TestEntity entity = new TestEntity();
        AIIndexDocument primary = upsert();
        IndexingQueueEntry primaryEntry = entry(81L);
        IndexingQueueEntry analysisEntry = entry(82L);
        primaryEntry.setStatus(IndexingStatus.COMMIT_PENDING);
        analysisEntry.setStatus(IndexingStatus.PENDING);
        when(descriptors.resolve(entity)).thenReturn(descriptor(true));
        when(descriptors.getByEntityType("product")).thenReturn(descriptor(true));
        when(projection.project(entity, AIProcessOperation.UPDATE, ""))
            .thenReturn(primary);
        when(queue.enqueueForSynchronousDispatch(primary))
            .thenReturn(primaryEntry);
        when(queue.enqueue(
            org.mockito.ArgumentMatchers.argThat(document ->
                document.workType() == AIIndexWorkType.ANALYZE
            ),
            eq(IndexingStrategy.SYNC),
            eq(81L)
        )).thenReturn(analysisEntry);
        when(queue.claimSynchronous(81L))
            .thenReturn(java.util.Optional.of(primaryEntry));
        when(processor.process(primaryEntry)).thenReturn(
            new IndexingWorkProcessor.WorkResult(
                IndexingDispatchStatus.COMPLETED,
                null
            )
        );

        gateway(projection, descriptors, queue, processor)
            .upsert(entity, AIProcessOperation.UPDATE, IndexingStrategy.SYNC);

        verify(queue).markCompleted(81L, null);
        verify(queue, never()).claimSynchronous(82L);
        verify(processor, never()).process(analysisEntry);
    }

    private DefaultAIEntityIndexingGateway gateway(
        AIEntityProjectionService projection,
        AIEntityDescriptorRegistry descriptors,
        IndexingQueueService queue,
        IndexingWorkProcessor processor
    ) {
        return new DefaultAIEntityIndexingGateway(
            projection,
            descriptors,
            queue,
            processor
        );
    }

    private AIEntityDescriptor descriptor(boolean analysisEnabled) {
        EntityIdentityResolver identityResolver = new EntityIdentityResolver() {
            @Override
            public boolean supports(Class<?> entityClass) {
                return entityClass == TestEntity.class;
            }

            @Override
            public Object resolveIdentity(Object entity) {
                return "p-1";
            }
        };
        return new AIEntityDescriptor(
            TestEntity.class,
            "product",
            identityResolver,
            "test",
            List.of(),
            List.of(),
            true,
            8_000,
            analysisEnabled
                ? new AIAnalysisPolicy(true, Set.of(AIProcessOperation.UPDATE))
                : AIAnalysisPolicy.disabled(),
            IndexingStrategy.ASYNC,
            IndexingStrategy.AUTO,
            IndexingStrategy.AUTO,
            IndexingStrategy.AUTO,
            NoMigrationRepository.class,
            "a".repeat(64),
            Set.of(),
            Map.of()
        );
    }

    private IndexingQueueEntry entry(long id) {
        IndexingQueueEntry entry = new IndexingQueueEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        entry.setEntityType("product");
        entry.setEntityId("p-1");
        entry.setWorkType(AIIndexWorkType.UPSERT);
        return entry;
    }

    private AIIndexDocument upsert() {
        return new AIIndexDocument(
            AIIndexDocument.CURRENT_SCHEMA_VERSION,
            "a".repeat(64),
            "product",
            "p-1",
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            "title: Laptop",
            "title: Laptop",
            Map.of(),
            Map.of(),
            Map.of(),
            1L,
            "",
            Instant.parse("2026-07-24T12:00:00Z")
        );
    }

    private static class TestEntity {
    }
}
