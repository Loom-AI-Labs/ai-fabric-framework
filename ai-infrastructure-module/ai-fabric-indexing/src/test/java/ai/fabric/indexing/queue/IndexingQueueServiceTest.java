package ai.fabric.indexing.queue;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingActionPlan;
import ai.fabric.indexing.IndexingOperation;
import ai.fabric.indexing.IndexingPriority;
import ai.fabric.indexing.IndexingRequest;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.repository.IndexingQueueRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingQueueServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-06-18T10:15:30Z"),
        ZoneOffset.UTC
    );
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 6, 18, 10, 15, 30);

    @Test
    void enqueueInitializesDurableQueueEntry() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingQueueService service = new IndexingQueueService(repository, new AIIndexingProperties(), FIXED_CLOCK);
        IndexingActionPlan plan = new IndexingActionPlan(true, true, false, false, false);
        LocalDateTime scheduledFor = NOW.plusMinutes(5);
        IndexingRequest request = IndexingRequest.builder()
            .entityType("product")
            .entityId("p-1")
            .entityClassName(Product.class.getName())
            .operation(IndexingOperation.CREATE)
            .strategy(IndexingStrategy.ASYNC)
            .actionPlan(plan)
            .payload("{\"id\":\"p-1\"}")
            .scheduledFor(scheduledFor)
            .maxRetries(7)
            .build();

        when(repository.save(any(IndexingQueueEntry.class))).thenAnswer(invocation -> invocation.getArgument(0));

        IndexingQueueEntry entry = service.enqueue(request);

        assertThat(entry.getEntityType()).isEqualTo("product");
        assertThat(entry.getEntityId()).isEqualTo("p-1");
        assertThat(entry.getEntityClass()).isEqualTo(Product.class.getName());
        assertThat(entry.getOperation()).isEqualTo(IndexingOperation.CREATE);
        assertThat(entry.getStrategy()).isEqualTo(IndexingStrategy.ASYNC);
        assertThat(entry.getPriority()).isEqualTo(IndexingPriority.HIGH);
        assertThat(entry.getPriorityWeight()).isEqualTo(IndexingPriority.HIGH.getWeight());
        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(entry.toActionPlan()).isEqualTo(plan);
        assertThat(entry.getPayload()).isEqualTo("{\"id\":\"p-1\"}");
        assertThat(entry.getMaxRetries()).isEqualTo(7);
        assertThat(entry.getRequestedAt()).isEqualTo(NOW);
        assertThat(entry.getCreatedAt()).isEqualTo(NOW);
        assertThat(entry.getUpdatedAt()).isEqualTo(NOW);
        assertThat(entry.getScheduledFor()).isEqualTo(scheduledFor);
        verify(repository).save(entry);
    }

    @Test
    void leaseNormalizesBatchSizeAndMarksEntriesProcessing() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getQueue().setVisibilityTimeout(Duration.ofMinutes(3));
        IndexingQueueService service = new IndexingQueueService(repository, properties, FIXED_CLOCK);
        IndexingQueueEntry entry = new IndexingQueueEntry();

        when(repository.findByStatusAndStrategyAndScheduledForLessThanEqualOrderByPriorityWeightAscRequestedAtAsc(
            eq(IndexingStatus.PENDING),
            eq(IndexingStrategy.BATCH),
            eq(NOW),
            any(Pageable.class)
        )).thenReturn(List.of(entry));

        List<IndexingQueueEntry> leased = service.lease(IndexingStrategy.BATCH, 0);

        assertThat(leased).containsExactly(entry);
        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PROCESSING);
        assertThat(entry.getStartedAt()).isEqualTo(NOW);
        assertThat(entry.getUpdatedAt()).isEqualTo(NOW);
        assertThat(entry.getProcessingNode()).isNotBlank();
        assertThat(entry.getVisibilityTimeoutUntil()).isEqualTo(NOW.plusMinutes(3));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByStatusAndStrategyAndScheduledForLessThanEqualOrderByPriorityWeightAscRequestedAtAsc(
            eq(IndexingStatus.PENDING),
            eq(IndexingStrategy.BATCH),
            eq(NOW),
            pageableCaptor.capture()
        );
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void markCompletedClearsLeaseAndPersistsCompletion() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingQueueService service = new IndexingQueueService(repository, new AIIndexingProperties(), FIXED_CLOCK);
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setProcessingNode("node-1");
        entry.setVisibilityTimeoutUntil(NOW.plusMinutes(2));

        service.markCompleted(entry);

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.COMPLETED);
        assertThat(entry.getProcessingNode()).isNull();
        assertThat(entry.getVisibilityTimeoutUntil()).isNull();
        assertThat(entry.getCompletedAt()).isEqualTo(NOW);
        assertThat(entry.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).save(entry);
    }

    @Test
    void markFailureSchedulesRetryWithExponentialBackoff() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingQueueService service = new IndexingQueueService(repository, new AIIndexingProperties(), FIXED_CLOCK);
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setId("entry-1");
        entry.setMaxRetries(3);
        entry.setRetryCount(0);
        entry.setProcessingNode("node-1");
        entry.setVisibilityTimeoutUntil(NOW.plusMinutes(2));

        service.markFailure(entry, "temporary failure");

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(entry.getRetryCount()).isEqualTo(1);
        assertThat(entry.getScheduledFor()).isEqualTo(NOW.plusSeconds(2));
        assertThat(entry.getProcessingNode()).isNull();
        assertThat(entry.getVisibilityTimeoutUntil()).isNull();
        assertThat(entry.getErrorMessage()).isEqualTo("temporary failure");
        assertThat(entry.getLastErrorAt()).isEqualTo(NOW);
        assertThat(entry.getDeadLetterReason()).isNull();
        verify(repository).save(entry);
    }

    @Test
    void markFailureMovesEntryToDeadLetterAtRetryLimit() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingQueueService service = new IndexingQueueService(repository, new AIIndexingProperties(), FIXED_CLOCK);
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setId("entry-2");
        entry.setMaxRetries(2);
        entry.setRetryCount(1);
        entry.setProcessingNode("node-1");
        entry.setVisibilityTimeoutUntil(NOW.plusMinutes(2));

        service.markFailure(entry, "permanent failure");

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.DEAD_LETTER);
        assertThat(entry.getRetryCount()).isEqualTo(2);
        assertThat(entry.getProcessingNode()).isNull();
        assertThat(entry.getVisibilityTimeoutUntil()).isNull();
        assertThat(entry.getDeadLetterReason()).isEqualTo("permanent failure");
        assertThat(entry.getUpdatedAt()).isEqualTo(NOW);
        verify(repository).save(entry);
    }

    private static class Product {
    }
}
