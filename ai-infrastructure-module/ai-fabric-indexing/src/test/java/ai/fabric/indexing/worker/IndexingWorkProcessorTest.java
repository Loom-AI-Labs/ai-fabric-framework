package ai.fabric.indexing.worker;

import ai.fabric.entity.IndexingEntityState;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingDispatchStatus;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.repository.IndexingEntityStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingWorkProcessorTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-24T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void executesAndPersistsAppliedSequence() {
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingEntityStateRepository states = mock(IndexingEntityStateRepository.class);
        IndexingOperationExecutor executor = mock(IndexingOperationExecutor.class);
        IndexingQueueEntry entry = entry(11L);
        AIIndexDocument document = document(5L);
        when(queue.readDocument(entry)).thenReturn(document);
        IndexingEntityState state = new IndexingEntityState(
            IndexingWorkProcessor.stateKey("product", "p-1"),
            "product",
            "p-1",
            LocalDateTime.now(CLOCK)
        );
        when(states.findForUpdate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(state));
        when(executor.execute(document, 11L)).thenReturn(null);

        var result = processor(queue, states, executor).process(entry);

        assertThat(result.status()).isEqualTo(IndexingDispatchStatus.COMPLETED);
        verify(executor).execute(document, 11L);
        verify(states).save(org.mockito.ArgumentMatchers.argThat(savedState ->
            savedState.getLastAppliedWorkId() == 11L
                && savedState.getLastSourceVersion().equals(5L)
        ));
    }

    @Test
    void createsMissingOrderingStateBeforeCallingTheProvider() {
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingEntityStateRepository states = mock(IndexingEntityStateRepository.class);
        IndexingOperationExecutor executor = mock(IndexingOperationExecutor.class);
        IndexingQueueEntry entry = entry(11L);
        AIIndexDocument document = document(5L);
        when(queue.readDocument(entry)).thenReturn(document);
        when(states.findForUpdate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.empty());
        when(states.saveAndFlush(
            org.mockito.ArgumentMatchers.any(IndexingEntityState.class)
        )).thenAnswer(invocation -> invocation.getArgument(0));

        var result = processor(queue, states, executor).process(entry);

        assertThat(result.status()).isEqualTo(IndexingDispatchStatus.COMPLETED);
        var order = inOrder(states, executor);
        order.verify(states).saveAndFlush(
            org.mockito.ArgumentMatchers.argThat(state ->
                state.getEntityType().equals("product")
                    && state.getEntityId().equals("p-1")
            )
        );
        order.verify(executor).execute(document, 11L);
    }

    @Test
    void skipsOlderWorkAfterDeleteTombstoneWithoutCallingProviders() {
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingEntityStateRepository states = mock(IndexingEntityStateRepository.class);
        IndexingOperationExecutor executor = mock(IndexingOperationExecutor.class);
        IndexingQueueEntry entry = entry(8L);
        AIIndexDocument document = document(4L);
        IndexingEntityState state = new IndexingEntityState(
            IndexingWorkProcessor.stateKey("product", "p-1"),
            "product",
            "p-1",
            LocalDateTime.now(CLOCK)
        );
        state.markApplied(12L, 6L, LocalDateTime.now(CLOCK));
        when(queue.readDocument(entry)).thenReturn(document);
        when(states.findForUpdate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(state));

        var result = processor(queue, states, executor).process(entry);

        assertThat(result.status()).isEqualTo(IndexingDispatchStatus.SKIPPED_STALE);
        verify(executor, never()).execute(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong()
        );
    }

    @Test
    void skipsLowerDomainVersionEvenWhenQueuedLater() {
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingEntityStateRepository states = mock(IndexingEntityStateRepository.class);
        IndexingOperationExecutor executor = mock(IndexingOperationExecutor.class);
        IndexingQueueEntry entry = entry(20L);
        AIIndexDocument document = document(3L);
        IndexingEntityState state = new IndexingEntityState(
            IndexingWorkProcessor.stateKey("product", "p-1"),
            "product",
            "p-1",
            LocalDateTime.now(CLOCK)
        );
        state.markApplied(15L, 7L, LocalDateTime.now(CLOCK));
        when(queue.readDocument(entry)).thenReturn(document);
        when(states.findForUpdate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(state));

        var result = processor(queue, states, executor).process(entry);

        assertThat(result.status()).isEqualTo(IndexingDispatchStatus.SKIPPED_STALE);
        verify(executor, never()).execute(document, 20L);
    }

    @Test
    void staleDeleteCannotRemoveAnEntityThatWasRecreatedByNewerWork() {
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingEntityStateRepository states = mock(IndexingEntityStateRepository.class);
        IndexingOperationExecutor executor = mock(IndexingOperationExecutor.class);
        IndexingQueueEntry staleDelete = entry(30L);
        AIIndexDocument deleteDocument = deleteDocument();
        IndexingEntityState state = new IndexingEntityState(
            IndexingWorkProcessor.stateKey("product", "p-1"),
            "product",
            "p-1",
            LocalDateTime.now(CLOCK)
        );
        state.markApplied(31L, 1L, LocalDateTime.now(CLOCK));
        when(queue.readDocument(staleDelete)).thenReturn(deleteDocument);
        when(states.findForUpdate(org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(Optional.of(state));

        var result = processor(queue, states, executor).process(staleDelete);

        assertThat(result.status()).isEqualTo(IndexingDispatchStatus.SKIPPED_STALE);
        verify(executor, never()).execute(deleteDocument, 30L);
    }

    @Test
    void doesNotRunAnalysisWhenItsUpsertDependencyDidNotComplete() {
        IndexingQueueService queue = mock(IndexingQueueService.class);
        IndexingEntityStateRepository states = mock(IndexingEntityStateRepository.class);
        IndexingOperationExecutor executor = mock(IndexingOperationExecutor.class);
        IndexingQueueEntry entry = entry(21L);
        when(queue.readDocument(entry)).thenReturn(document(8L));
        when(queue.isDependencyCompleted(entry)).thenReturn(false);

        var result = new IndexingWorkProcessor(
            queue,
            states,
            executor,
            CLOCK
        ).process(entry);

        assertThat(result.status()).isEqualTo(IndexingDispatchStatus.SKIPPED_STALE);
        verify(executor, never()).execute(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyLong()
        );
        verify(states, never()).findForUpdate(
            org.mockito.ArgumentMatchers.anyString()
        );
    }

    private IndexingWorkProcessor processor(
        IndexingQueueService queue,
        IndexingEntityStateRepository states,
        IndexingOperationExecutor executor
    ) {
        when(queue.isDependencyCompleted(
            org.mockito.ArgumentMatchers.any(IndexingQueueEntry.class)
        )).thenReturn(true);
        return new IndexingWorkProcessor(queue, states, executor, CLOCK);
    }

    private IndexingQueueEntry entry(long id) {
        IndexingQueueEntry entry = new IndexingQueueEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        return entry;
    }

    private AIIndexDocument document(long sourceVersion) {
        return new AIIndexDocument(
            1,
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
            sourceVersion,
            "",
            Instant.parse("2026-07-24T12:00:00Z")
        );
    }

    private AIIndexDocument deleteDocument() {
        return new AIIndexDocument(
            1,
            "a".repeat(64),
            "product",
            "p-1",
            AIIndexWorkType.DELETE,
            AIProcessOperation.DELETE,
            null,
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            null,
            "",
            Instant.parse("2026-07-24T12:00:00Z")
        );
    }
}
