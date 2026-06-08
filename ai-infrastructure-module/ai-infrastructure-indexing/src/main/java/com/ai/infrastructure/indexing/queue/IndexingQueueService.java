package com.ai.infrastructure.indexing.queue;

import com.ai.infrastructure.config.AIIndexingProperties;
import com.ai.infrastructure.entity.IndexingQueueEntry;
import com.ai.infrastructure.indexing.IndexingPriority;
import com.ai.infrastructure.indexing.IndexingRequest;
import com.ai.infrastructure.indexing.IndexingStatus;
import com.ai.infrastructure.indexing.api.IndexingStrategy;
import com.ai.infrastructure.repository.IndexingQueueRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Durable queue gateway used by the indexing orchestration components.
 */
@Slf4j
@Transactional
public class IndexingQueueService {

    private final IndexingQueueRepository repository;
    private final AIIndexingProperties properties;
    private final Clock clock;

    public IndexingQueueService(
        IndexingQueueRepository repository,
        AIIndexingProperties properties,
        Clock clock
    ) {
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    public IndexingQueueEntry enqueue(IndexingRequest request) {
        LocalDateTime now = now();
        IndexingQueueEntry entry = new IndexingQueueEntry();
        entry.setEntityType(request.entityType());
        entry.setEntityId(request.entityId());
        entry.setEntityClass(request.entityClassName());
        entry.setOperation(request.operation());
        entry.applyActionPlan(request.actionPlan());
        entry.setPayload(request.payload());
        entry.setScheduledFor(request.scheduledFor());
        entry.setMaxRetries(request.maxRetries());
        entry.setStrategy(request.strategy());
        IndexingPriority priority = IndexingPriority.fromStrategy(request.strategy());
        entry.initialize(request.strategy(), priority, now);
        entry.setUpdatedAt(now);
        return repository.save(entry);
    }

    public List<IndexingQueueEntry> lease(IndexingStrategy strategy, int batchSize) {
        LocalDateTime now = now();
        List<IndexingQueueEntry> pending = repository
            .findByStatusAndStrategyAndScheduledForLessThanEqualOrderByPriorityWeightAscRequestedAtAsc(
                IndexingStatus.PENDING,
                strategy,
                now,
                PageRequest.of(0, batchSize)
            );

        for (IndexingQueueEntry entry : pending) {
            entry.setStatus(IndexingStatus.PROCESSING);
            entry.setStartedAt(now);
            entry.setUpdatedAt(now);
            entry.setProcessingNode(entry.assignProcessingNode());
            entry.setVisibilityTimeoutUntil(now.plus(properties.getQueue().getVisibilityTimeout()));
        }

        return pending;
    }

    public void markCompleted(IndexingQueueEntry entry) {
        LocalDateTime now = now();
        entry.setStatus(IndexingStatus.COMPLETED);
        entry.setProcessingNode(null);
        entry.setCompletedAt(now);
        entry.setUpdatedAt(now);
        entry.setVisibilityTimeoutUntil(null);
        repository.save(entry);
    }

    public void markFailure(IndexingQueueEntry entry, String errorMessage) {
        LocalDateTime now = now();
        entry.setErrorMessage(errorMessage);
        entry.setLastErrorAt(now);
        entry.setProcessingNode(null);
        entry.setUpdatedAt(now);

        int attempts = entry.getRetryCount() + 1;
        entry.setRetryCount(attempts);

        if (attempts >= entry.getMaxRetries()) {
            entry.setStatus(IndexingStatus.DEAD_LETTER);
            entry.setDeadLetterReason(errorMessage);
            log.error("Indexing entry {} moved to dead letter after {} attempts: {}", entry.getId(), attempts, errorMessage);
        } else {
            entry.setStatus(IndexingStatus.PENDING);
            long delaySeconds = Math.min(300, (long) Math.pow(2, attempts));
            entry.setScheduledFor(now.plusSeconds(delaySeconds));
            entry.setVisibilityTimeoutUntil(null);
            log.warn("Indexing entry {} will be retried in {} seconds (attempt {}/{})",
                entry.getId(), delaySeconds, attempts, entry.getMaxRetries());
        }

        repository.save(entry);
    }

    public int resetStuckEntries() {
        LocalDateTime now = now();
        int updated = repository.resetExpiredVisibilityTimeouts(
            IndexingStatus.PROCESSING,
            IndexingStatus.PENDING,
            now
        );
        if (updated > 0) {
            log.warn("Reset {} stuck indexing entries", updated);
        }
        return updated;
    }

    public int purgeCompletedOlderThan(LocalDateTime olderThan) {
        return repository.deleteByStatusAndCompletedAtBefore(IndexingStatus.COMPLETED, olderThan);
    }

    public int purgeDeadLettersOlderThan(LocalDateTime olderThan) {
        return repository.deleteByStatusAndUpdatedAtBefore(IndexingStatus.DEAD_LETTER, olderThan);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
