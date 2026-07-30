package ai.fabric.indexing.query;

import ai.fabric.entity.IndexingQueueEntry;
import ai.fabric.indexing.IndexingStatus;
import ai.fabric.indexing.api.IndexingWorkQuery;
import ai.fabric.indexing.api.IndexingWorkState;
import ai.fabric.indexing.api.IndexingWorkStatus;
import ai.fabric.repository.IndexingQueueRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * Repository-backed projection of indexing queue state into the public API.
 */
public class DefaultIndexingWorkQuery implements IndexingWorkQuery {

    private final IndexingQueueRepository repository;

    public DefaultIndexingWorkQuery(IndexingQueueRepository repository) {
        this.repository = Objects.requireNonNull(repository);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IndexingWorkStatus> findByWorkId(String workId) {
        long numericWorkId = parseWorkId(workId);
        return repository.findById(numericWorkId).map(this::toStatus);
    }

    private IndexingWorkStatus toStatus(IndexingQueueEntry entry) {
        return new IndexingWorkStatus(
            String.valueOf(entry.getId()),
            entry.getEntityType(),
            entry.getEntityId(),
            entry.getWorkType(),
            entry.getSourceOperation(),
            entry.getStrategy(),
            toPublicState(entry.getStatus()),
            entry.getRetryCount(),
            entry.getMaxRetries(),
            entry.getErrorCode(),
            entry.getDeadLetterReason(),
            entry.getCorrelationId(),
            entry.getRequestedAt(),
            entry.getScheduledFor(),
            entry.getStartedAt(),
            entry.getCompletedAt(),
            entry.getLastErrorAt(),
            entry.getUpdatedAt()
        );
    }

    private IndexingWorkState toPublicState(IndexingStatus status) {
        Objects.requireNonNull(status, "Persisted indexing status is required");
        return switch (status) {
            case COMMIT_PENDING -> IndexingWorkState.COMMIT_PENDING;
            case PENDING -> IndexingWorkState.PENDING;
            case PROCESSING -> IndexingWorkState.PROCESSING;
            case COMPLETED -> IndexingWorkState.COMPLETED;
            case SUPERSEDED -> IndexingWorkState.SUPERSEDED;
            case DEAD_LETTER -> IndexingWorkState.DEAD_LETTER;
        };
    }

    private long parseWorkId(String workId) {
        if (workId == null || workId.isBlank()) {
            throw new IllegalArgumentException("workId is required");
        }
        try {
            long parsed = Long.parseLong(workId.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException(
                    "workId must be a positive integer"
                );
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                "workId must be a positive integer",
                exception
            );
        }
    }
}
