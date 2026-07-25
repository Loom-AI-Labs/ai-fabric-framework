package ai.fabric.indexing.queue;

import ai.fabric.config.AIIndexingProperties;
import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.indexing.model.AIIndexDocument;
import ai.fabric.indexing.observability.IndexingMetrics;
import ai.fabric.repository.IndexingQueueRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable queue gateway for approved, versioned index documents.
 */
public class IndexingQueueService {

    private static final int STUCK_RECLAIM_BATCH_SIZE = 500;
    private static final int COMMIT_RECOVERY_BATCH_SIZE = 500;
    private static final String VISIBILITY_TIMEOUT_ERROR =
        "WORKER_VISIBILITY_TIMEOUT";
    private static final String COMMIT_DISPATCH_TIMEOUT_ERROR =
        "SYNC_COMMIT_DISPATCH_TIMEOUT";

    private static final List<IndexingStatus> BLOCKING_STATUSES = List.of(
        IndexingStatus.COMMIT_PENDING,
        IndexingStatus.PENDING,
        IndexingStatus.PROCESSING
    );

    private final IndexingQueueRepository repository;
    private final AIIndexingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final IndexingMetrics metrics;

    public IndexingQueueService(
        IndexingQueueRepository repository,
        AIIndexingProperties properties,
        ObjectMapper objectMapper,
        Clock clock,
        IndexingMetrics metrics
    ) {
        this.repository = Objects.requireNonNull(repository);
        this.properties = Objects.requireNonNull(properties);
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.clock = Objects.requireNonNull(clock);
        this.metrics = Objects.requireNonNull(metrics);
    }

    public IndexingQueueService(
        IndexingQueueRepository repository,
        AIIndexingProperties properties,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        this(
            repository,
            properties,
            objectMapper,
            clock,
            new IndexingMetrics(null)
        );
    }

    @Transactional
    public IndexingQueueEntry enqueue(
        AIIndexDocument document,
        IndexingStrategy strategy
    ) {
        return enqueue(
            document,
            strategy,
            now(),
            null,
            IndexingStatus.PENDING
        );
    }

    @Transactional
    public IndexingQueueEntry enqueueForSynchronousDispatch(
        AIIndexDocument document
    ) {
        return enqueue(
            document,
            IndexingStrategy.SYNC,
            now(),
            null,
            IndexingStatus.COMMIT_PENDING
        );
    }

    @Transactional
    public IndexingQueueEntry enqueue(
        AIIndexDocument document,
        IndexingStrategy strategy,
        LocalDateTime scheduledFor
    ) {
        return enqueue(
            document,
            strategy,
            scheduledFor,
            null,
            IndexingStatus.PENDING
        );
    }

    @Transactional
    public IndexingQueueEntry enqueue(
        AIIndexDocument document,
        IndexingStrategy strategy,
        Long dependsOnWorkId
    ) {
        return enqueue(
            document,
            strategy,
            now(),
            dependsOnWorkId,
            IndexingStatus.PENDING
        );
    }

    @Transactional
    public IndexingQueueEntry enqueueForSynchronousDispatch(
        AIIndexDocument document,
        Long dependsOnWorkId
    ) {
        return enqueue(
            document,
            IndexingStrategy.SYNC,
            now(),
            dependsOnWorkId,
            IndexingStatus.COMMIT_PENDING
        );
    }

    @Transactional
    public IndexingQueueEntry enqueue(
        AIIndexDocument document,
        IndexingStrategy strategy,
        LocalDateTime scheduledFor,
        Long dependsOnWorkId
    ) {
        return enqueue(
            document,
            strategy,
            scheduledFor,
            dependsOnWorkId,
            IndexingStatus.PENDING
        );
    }

    private IndexingQueueEntry enqueue(
        AIIndexDocument document,
        IndexingStrategy strategy,
        LocalDateTime scheduledFor,
        Long dependsOnWorkId,
        IndexingStatus initialStatus
    ) {
        Objects.requireNonNull(document, "document is required");
        if (strategy == null || strategy == IndexingStrategy.AUTO) {
            throw new IllegalArgumentException("A concrete indexing strategy is required");
        }
        if (initialStatus == IndexingStatus.COMMIT_PENDING
            && strategy != IndexingStrategy.SYNC) {
            throw new IllegalArgumentException(
                "Only SYNC work can be reserved for commit dispatch"
            );
        }

        LocalDateTime now = now();
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setEntityType(document.entityType());
        entry.setEntityId(document.entityId());
        entry.setWorkType(document.workType());
        entry.setSourceOperation(document.sourceOperation());
        entry.setStrategy(strategy);
        entry.setStatus(initialStatus);
        entry.setPayloadSchemaVersion(document.schemaVersion());
        entry.setDescriptorHash(document.descriptorHash());
        entry.setCorrelationId(document.correlationId());
        entry.setDependsOnWorkId(dependsOnWorkId);
        entry.setPayload(serialize(document));
        entry.setMaxRetries(Math.max(1, properties.getQueue().getMaxRetries()));
        entry.setRequestedAt(now);
        entry.setScheduledFor(scheduledFor == null ? now : scheduledFor);
        entry.setCreatedAt(now);
        entry.setUpdatedAt(now);
        IndexingQueueEntry saved = repository.saveAndFlush(entry);
        afterCommit(() -> metrics.accepted(document, strategy));
        return saved;
    }

    @Transactional
    public List<IndexingQueueEntry> lease(
        IndexingStrategy strategy,
        int batchSize
    ) {
        LocalDateTime now = now();
        List<IndexingQueueEntry> entries = repository.leaseReady(
            IndexingStatus.PENDING,
            strategy,
            BLOCKING_STATUSES,
            now,
            PageRequest.of(0, Math.max(1, batchSize))
        );
        for (IndexingQueueEntry entry : entries) {
            entry.setStatus(IndexingStatus.PROCESSING);
            entry.setStartedAt(now);
            entry.setUpdatedAt(now);
            entry.setProcessingNode(UUID.randomUUID().toString());
            entry.setVisibilityTimeoutUntil(
                now.plus(properties.getQueue().getVisibilityTimeout())
            );
        }
        return entries;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<IndexingQueueEntry> claimSynchronous(long workId) {
        LocalDateTime now = now();
        Optional<IndexingQueueEntry> candidate =
            repository.findReadySynchronousForUpdate(
                workId,
                List.of(IndexingStatus.COMMIT_PENDING, IndexingStatus.PENDING),
                BLOCKING_STATUSES
            );
        if (candidate.isEmpty()
            || (candidate.get().getStatus() != IndexingStatus.COMMIT_PENDING
                && candidate.get().getStatus() != IndexingStatus.PENDING)) {
            return Optional.empty();
        }
        IndexingQueueEntry entry = candidate.get();
        entry.setStatus(IndexingStatus.PROCESSING);
        entry.setStartedAt(now);
        entry.setUpdatedAt(now);
        entry.setProcessingNode("sync-" + UUID.randomUUID());
        entry.setVisibilityTimeoutUntil(
            now.plus(properties.getQueue().getVisibilityTimeout())
        );
        return Optional.of(entry);
    }

    @Transactional(readOnly = true)
    public IndexingQueueEntry requireEntry(long workId) {
        return repository.findById(workId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown indexing work " + workId
            ));
    }

    public AIIndexDocument readDocument(IndexingQueueEntry entry) {
        Objects.requireNonNull(entry, "entry is required");
        try {
            AIIndexDocument document = objectMapper.readValue(
                entry.getPayload(),
                AIIndexDocument.class
            );
            if (document.schemaVersion() != entry.getPayloadSchemaVersion()) {
                throw new IndexingPayloadException("PAYLOAD_SCHEMA_MISMATCH");
            }
            if (!document.descriptorHash().equals(entry.getDescriptorHash())) {
                throw new IndexingPayloadException("DESCRIPTOR_HASH_MISMATCH");
            }
            if (!document.entityType().equals(entry.getEntityType())
                || !document.entityId().equals(entry.getEntityId())
                || document.workType() != entry.getWorkType()
                || document.sourceOperation() != entry.getSourceOperation()) {
                throw new IndexingPayloadException("PAYLOAD_ENVELOPE_MISMATCH");
            }
            return document;
        } catch (IndexingPayloadException exception) {
            throw exception;
        } catch (JsonProcessingException exception) {
            throw new IndexingPayloadException("PAYLOAD_DESERIALIZATION_FAILED", exception);
        }
    }

    @Transactional(readOnly = true)
    public boolean isDependencyCompleted(IndexingQueueEntry entry) {
        if (entry.getDependsOnWorkId() == null) {
            return true;
        }
        return repository.findById(entry.getDependsOnWorkId())
            .map(dependency -> dependency.getStatus() == IndexingStatus.COMPLETED)
            .orElse(false);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markCompleted(long workId, String resultPayload) {
        IndexingQueueEntry entry = requireMutable(workId);
        LocalDateTime now = now();
        entry.setStatus(IndexingStatus.COMPLETED);
        entry.setProcessingNode(null);
        entry.setCompletedAt(now);
        entry.setUpdatedAt(now);
        entry.setVisibilityTimeoutUntil(null);
        entry.setErrorCode(null);
        entry.setResultPayload(resultPayload);
        metrics.completed(
            entry,
            Duration.between(entry.getRequestedAt(), now)
        );
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSuperseded(long workId) {
        IndexingQueueEntry entry = requireMutable(workId);
        LocalDateTime now = now();
        entry.setStatus(IndexingStatus.SUPERSEDED);
        entry.setProcessingNode(null);
        entry.setCompletedAt(now);
        entry.setUpdatedAt(now);
        entry.setVisibilityTimeoutUntil(null);
        entry.setErrorCode(null);
        metrics.superseded(entry);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailure(long workId, String errorCode) {
        IndexingQueueEntry entry = requireMutable(workId);
        applyFailure(entry, errorCode, now());
    }

    private void applyFailure(
        IndexingQueueEntry entry,
        String errorCode,
        LocalDateTime now
    ) {
        String safeCode = safeCode(errorCode);
        entry.setErrorCode(safeCode);
        entry.setLastErrorAt(now);
        entry.setProcessingNode(null);
        entry.setUpdatedAt(now);
        entry.setVisibilityTimeoutUntil(null);

        int attempts = entry.getRetryCount() + 1;
        entry.setRetryCount(attempts);
        if (attempts >= entry.getMaxRetries()) {
            entry.setStatus(IndexingStatus.DEAD_LETTER);
            entry.setDeadLetterReason(safeCode);
            metrics.failed(entry, true);
            return;
        }

        entry.setStatus(IndexingStatus.PENDING);
        long delaySeconds = Math.min(300, 1L << Math.min(attempts, 8));
        entry.setScheduledFor(now.plusSeconds(delaySeconds));
        metrics.failed(entry, false);
    }

    @Transactional
    public int resetStuckEntries() {
        Duration stuckThreshold = properties.getCleanup().getStuckThreshold();
        if (stuckThreshold == null || stuckThreshold.isNegative()) {
            throw new IllegalStateException(
                "ai.indexing.cleanup.stuck-threshold must be zero or positive"
            );
        }

        LocalDateTime now = now();
        List<IndexingQueueEntry> stuck = repository.findStuckForUpdate(
            IndexingStatus.PROCESSING,
            now,
            now.minus(stuckThreshold),
            PageRequest.of(0, STUCK_RECLAIM_BATCH_SIZE)
        );
        stuck.forEach(entry ->
            applyFailure(entry, VISIBILITY_TIMEOUT_ERROR, now)
        );
        return stuck.size();
    }

    @Transactional
    public int releaseOrphanedSynchronousEntries() {
        Duration recoveryTimeout = properties.getQueue()
            .getSyncCommitRecoveryTimeout();
        if (recoveryTimeout == null || recoveryTimeout.isZero()
            || recoveryTimeout.isNegative()) {
            throw new IllegalStateException(
                "ai.indexing.queue.sync-commit-recovery-timeout must be positive"
            );
        }

        LocalDateTime now = now();
        List<IndexingQueueEntry> orphaned = repository.findCommitPendingForUpdate(
            IndexingStatus.COMMIT_PENDING,
            IndexingStrategy.SYNC,
            now.minus(recoveryTimeout),
            PageRequest.of(0, COMMIT_RECOVERY_BATCH_SIZE)
        );
        for (IndexingQueueEntry entry : orphaned) {
            entry.setStatus(IndexingStatus.PENDING);
            entry.setScheduledFor(now);
            entry.setUpdatedAt(now);
            entry.setErrorCode(COMMIT_DISPATCH_TIMEOUT_ERROR);
            entry.setLastErrorAt(now);
        }
        return orphaned.size();
    }

    @Transactional
    public int purgeCompletedOlderThan(LocalDateTime olderThan) {
        return repository.deleteByStatusInAndCompletedAtBefore(
            List.of(IndexingStatus.COMPLETED, IndexingStatus.SUPERSEDED),
            olderThan
        );
    }

    @Transactional
    public int purgeDeadLettersOlderThan(LocalDateTime olderThan) {
        return repository.deleteByStatusAndUpdatedAtBefore(
            IndexingStatus.DEAD_LETTER,
            olderThan
        );
    }

    private IndexingQueueEntry requireMutable(long workId) {
        return repository.findById(workId)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown indexing work " + workId
            ));
    }

    private String serialize(AIIndexDocument document) {
        try {
            return objectMapper.writeValueAsString(document);
        } catch (JsonProcessingException exception) {
            throw new IndexingPayloadException("PAYLOAD_SERIALIZATION_FAILED", exception);
        }
    }

    private String safeCode(String errorCode) {
        String candidate = errorCode == null || errorCode.isBlank()
            ? "INDEXING_FAILURE"
            : errorCode.trim().toUpperCase(java.util.Locale.ROOT);
        candidate = candidate.replaceAll("[^A-Z0-9_.-]", "_");
        return candidate.substring(0, Math.min(candidate.length(), 128));
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void afterCommit(Runnable action) {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager
            .isSynchronizationActive()) {
            action.run();
            return;
        }
        org.springframework.transaction.support.TransactionSynchronizationManager
            .registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                }
            );
    }
}
