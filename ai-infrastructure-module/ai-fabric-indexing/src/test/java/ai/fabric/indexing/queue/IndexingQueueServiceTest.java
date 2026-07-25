package ai.fabric.indexing.queue;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.AIIndexWorkType;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.observability.IndexingMetrics;
import ai.fabric.repository.IndexingQueueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IndexingQueueServiceTest {

    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-07-24T12:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void persistsAndReadsOnlyTheVersionedProjectionEnvelope() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            IndexingQueueEntry entry = invocation.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", 41L);
            return entry;
        });
        IndexingQueueService service = service(repository);
        AIIndexDocument document = document();

        IndexingQueueEntry entry = service.enqueue(document, IndexingStrategy.ASYNC);

        assertThat(entry.getId()).isEqualTo(41L);
        assertThat(entry.getEntityType()).isEqualTo("product");
        assertThat(entry.getEntityId()).isEqualTo("p-1");
        assertThat(entry.getPayload()).contains("\"semanticSearchText\":\"title: Laptop\"");
        assertThat(entry.getPayload()).doesNotContain("entityClass");
        assertThat(service.readDocument(entry)).isEqualTo(document);
    }

    @Test
    void rejectsTamperedEnvelopeWithoutExposingPayload() {
        IndexingQueueService service = service(mock(IndexingQueueRepository.class));
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setEntityType("other");
        entry.setEntityId("p-1");
        entry.setWorkType(AIIndexWorkType.UPSERT);
        entry.setSourceOperation(AIProcessOperation.UPDATE);
        entry.setPayloadSchemaVersion(1);
        entry.setDescriptorHash("a".repeat(64));
        try {
            entry.setPayload(new ObjectMapper().findAndRegisterModules()
                .writeValueAsString(document()));
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        assertThatThrownBy(() -> service.readDocument(entry))
            .isInstanceOf(IndexingPayloadException.class)
            .hasMessage("PAYLOAD_ENVELOPE_MISMATCH")
            .hasMessageNotContaining("Laptop");
    }

    @Test
    void retriesWithSafeErrorCodeAndMovesToDeadLetterAtLimit() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingQueueEntry entry = persistedEntry(9L);
        entry.setStatus(IndexingStatus.PROCESSING);
        entry.setMaxRetries(2);
        when(repository.findById(9L)).thenReturn(java.util.Optional.of(entry));
        IndexingQueueService service = service(repository);

        service.markFailure(9L, "provider failed: raw user text");

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(entry.getErrorCode()).isEqualTo("PROVIDER_FAILED__RAW_USER_TEXT");
        assertThat(entry.getRetryCount()).isEqualTo(1);
        assertThat(entry.getScheduledFor())
            .isEqualTo(LocalDateTime.of(2026, 7, 24, 12, 0, 2));

        service.markFailure(9L, "provider failed again");

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.DEAD_LETTER);
        assertThat(entry.getDeadLetterReason()).isEqualTo("PROVIDER_FAILED_AGAIN");
    }

    @Test
    void synchronousClaimEstablishesExclusiveProcessingOwnership() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingQueueEntry entry = persistedEntry(17L);
        entry.setStatus(IndexingStatus.PENDING);
        when(repository.findReadySynchronousForUpdate(
            eq(17L),
            any(),
            any()
        )).thenReturn(Optional.of(entry));
        IndexingQueueService service = service(repository);

        Optional<IndexingQueueEntry> claimed = service.claimSynchronous(17L);
        Optional<IndexingQueueEntry> duplicateClaim = service.claimSynchronous(17L);

        assertThat(claimed).containsSame(entry);
        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PROCESSING);
        assertThat(entry.getProcessingNode()).startsWith("sync-");
        assertThat(entry.getVisibilityTimeoutUntil())
            .isEqualTo(LocalDateTime.of(2026, 7, 24, 12, 2));
        assertThat(duplicateClaim).isEmpty();
    }

    @Test
    void commitOwnedSynchronousWorkCannotBeLeasedByTheRetryWorker() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        when(repository.saveAndFlush(any())).thenAnswer(invocation -> {
            IndexingQueueEntry entry = invocation.getArgument(0);
            ReflectionTestUtils.setField(entry, "id", 18L);
            return entry;
        });
        IndexingQueueService service = service(repository);

        IndexingQueueEntry entry = service.enqueueForSynchronousDispatch(
            document()
        );
        when(repository.findReadySynchronousForUpdate(
            eq(18L),
            any(),
            any()
        )).thenReturn(Optional.of(entry));

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.COMMIT_PENDING);
        assertThat(service.lease(IndexingStrategy.SYNC, 10)).isEmpty();
        verify(repository).leaseReady(
            eq(IndexingStatus.PENDING),
            eq(IndexingStrategy.SYNC),
            org.mockito.ArgumentMatchers.argThat(statuses ->
                statuses.contains(IndexingStatus.COMMIT_PENDING)
            ),
            eq(LocalDateTime.of(2026, 7, 24, 12, 0)),
            any(Pageable.class)
        );

        assertThat(service.claimSynchronous(18L)).containsSame(entry);
        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PROCESSING);
    }

    @Test
    void reclaimsOnlyEntriesPastTheConfiguredStuckThresholdWithRetryBackoff() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingMetrics metrics = mock(IndexingMetrics.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getCleanup().setStuckThreshold(Duration.ofMinutes(10));
        IndexingQueueEntry entry = persistedEntry(23L);
        entry.setEntityType("product");
        entry.setStrategy(IndexingStrategy.ASYNC);
        entry.setSourceOperation(AIProcessOperation.UPDATE);
        entry.setStatus(IndexingStatus.PROCESSING);
        entry.setMaxRetries(2);
        entry.setProcessingNode("worker-a");
        entry.setVisibilityTimeoutUntil(LocalDateTime.of(2026, 7, 24, 11, 59));
        when(repository.findStuckForUpdate(
            eq(IndexingStatus.PROCESSING),
            eq(LocalDateTime.of(2026, 7, 24, 12, 0)),
            eq(LocalDateTime.of(2026, 7, 24, 11, 50)),
            any(Pageable.class)
        )).thenReturn(List.of(entry));
        IndexingQueueService service = service(repository, properties, metrics);

        int reclaimed = service.resetStuckEntries();

        assertThat(reclaimed).isOne();
        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(entry.getRetryCount()).isEqualTo(1);
        assertThat(entry.getErrorCode()).isEqualTo("WORKER_VISIBILITY_TIMEOUT");
        assertThat(entry.getProcessingNode()).isNull();
        assertThat(entry.getVisibilityTimeoutUntil()).isNull();
        assertThat(entry.getScheduledFor())
            .isEqualTo(LocalDateTime.of(2026, 7, 24, 12, 0, 2));
        verify(metrics).failed(entry, false);
    }

    @Test
    void expiredWorkerLeaseMovesToDeadLetterAtTheRetryLimit() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        IndexingMetrics metrics = mock(IndexingMetrics.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        IndexingQueueEntry entry = persistedEntry(24L);
        entry.setEntityType("product");
        entry.setStrategy(IndexingStrategy.ASYNC);
        entry.setSourceOperation(AIProcessOperation.UPDATE);
        entry.setStatus(IndexingStatus.PROCESSING);
        entry.setRetryCount(1);
        entry.setMaxRetries(2);
        when(repository.findStuckForUpdate(
            eq(IndexingStatus.PROCESSING),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            any(Pageable.class)
        )).thenReturn(List.of(entry));
        IndexingQueueService service = service(repository, properties, metrics);

        service.resetStuckEntries();

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.DEAD_LETTER);
        assertThat(entry.getRetryCount()).isEqualTo(2);
        assertThat(entry.getDeadLetterReason())
            .isEqualTo("WORKER_VISIBILITY_TIMEOUT");
        verify(metrics).failed(entry, true);
    }

    @Test
    void orphanedCommitDispatchBecomesRetryableWithoutConsumingAnAttempt() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getQueue().setSyncCommitRecoveryTimeout(
            Duration.ofMinutes(10)
        );
        IndexingQueueEntry entry = persistedEntry(25L);
        entry.setEntityType("product");
        entry.setEntityId("p-1");
        entry.setStrategy(IndexingStrategy.SYNC);
        entry.setSourceOperation(AIProcessOperation.UPDATE);
        entry.setStatus(IndexingStatus.COMMIT_PENDING);
        entry.setRequestedAt(LocalDateTime.of(2026, 7, 24, 11, 49));
        when(repository.findCommitPendingForUpdate(
            eq(IndexingStatus.COMMIT_PENDING),
            eq(IndexingStrategy.SYNC),
            eq(LocalDateTime.of(2026, 7, 24, 11, 50)),
            any(Pageable.class)
        )).thenReturn(List.of(entry));
        IndexingQueueService service = service(
            repository,
            properties,
            new IndexingMetrics(null)
        );

        assertThat(service.releaseOrphanedSynchronousEntries()).isOne();

        assertThat(entry.getStatus()).isEqualTo(IndexingStatus.PENDING);
        assertThat(entry.getRetryCount()).isZero();
        assertThat(entry.getScheduledFor())
            .isEqualTo(LocalDateTime.of(2026, 7, 24, 12, 0));
        assertThat(entry.getErrorCode())
            .isEqualTo("SYNC_COMMIT_DISPATCH_TIMEOUT");
        assertThat(entry.getLastErrorAt())
            .isEqualTo(LocalDateTime.of(2026, 7, 24, 12, 0));
    }

    @Test
    void rejectsNonPositiveCommitRecoveryTimeout() {
        IndexingQueueRepository repository = mock(IndexingQueueRepository.class);
        AIIndexingProperties properties = new AIIndexingProperties();
        properties.getQueue().setSyncCommitRecoveryTimeout(Duration.ZERO);
        IndexingQueueService service = service(
            repository,
            properties,
            new IndexingMetrics(null)
        );

        assertThatThrownBy(service::releaseOrphanedSynchronousEntries)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("sync-commit-recovery-timeout");
    }

    private IndexingQueueService service(IndexingQueueRepository repository) {
        return service(
            repository,
            new AIIndexingProperties(),
            new IndexingMetrics(null)
        );
    }

    private IndexingQueueService service(
        IndexingQueueRepository repository,
        AIIndexingProperties properties,
        IndexingMetrics metrics
    ) {
        return new IndexingQueueService(
            repository,
            properties,
            new ObjectMapper().findAndRegisterModules(),
            CLOCK,
            metrics
        );
    }

    private IndexingQueueEntry persistedEntry(long id) {
        IndexingQueueEntry entry = new IndexingQueueEntry();
        ReflectionTestUtils.setField(entry, "id", id);
        entry.setScheduledFor(LocalDateTime.now(CLOCK));
        entry.setUpdatedAt(LocalDateTime.now(CLOCK));
        return entry;
    }

    private AIIndexDocument document() {
        return new AIIndexDocument(
            1,
            "a".repeat(64),
            "product",
            "p-1",
            AIIndexWorkType.UPSERT,
            AIProcessOperation.UPDATE,
            "title: Laptop",
            "details: Laptop evidence",
            Map.of("tenantId", "tenant-a"),
            Map.of(),
            Map.of(),
            3L,
            "trace",
            Instant.parse("2026-07-24T11:59:00Z")
        );
    }
}
