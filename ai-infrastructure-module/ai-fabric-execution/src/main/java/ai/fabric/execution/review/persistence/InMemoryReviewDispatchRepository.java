package ai.fabric.execution.review.persistence;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryReviewDispatchRepository
    implements ReviewDispatchRepository {

    private final ConcurrentMap<String, ReviewDispatchRecord> dispatches =
        new ConcurrentHashMap<>();

    @Override
    public synchronized ReviewDispatchRecord create(
        ReviewDispatchRecord dispatch
    ) {
        boolean duplicateIdempotency = dispatches.values().stream()
            .anyMatch(existing -> existing.idempotencyKey().equals(
                dispatch.idempotencyKey()
            ));
        if (dispatches.containsKey(dispatch.dispatchId())
            || duplicateIdempotency) {
            throw new DuplicateDispatchException(
                "Duplicate review dispatch ID or idempotency key",
                null
            );
        }
        dispatches.put(dispatch.dispatchId(), dispatch);
        return dispatch;
    }

    @Override
    public Optional<ReviewDispatchRecord> findById(String dispatchId) {
        return Optional.ofNullable(dispatches.get(dispatchId));
    }

    @Override
    public List<ReviewDispatchRecord> findByTaskId(String taskId) {
        return dispatches.values().stream()
            .filter(dispatch -> dispatch.taskId().equals(taskId))
            .sorted(Comparator.comparingInt(
                ReviewDispatchRecord::attemptNumber
            ))
            .toList();
    }

    @Override
    public boolean compareAndSet(
        ReviewDispatchRecord expected,
        ReviewDispatchRecord updated
    ) {
        if (!expected.dispatchId().equals(updated.dispatchId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Dispatch transition must preserve ID and increment version"
            );
        }
        return dispatches.replace(
            expected.dispatchId(),
            expected,
            updated
        );
    }

    @Override
    public synchronized int deleteByTaskId(String taskId) {
        List<String> matching = dispatches.values().stream()
            .filter(dispatch -> dispatch.taskId().equals(taskId))
            .map(ReviewDispatchRecord::dispatchId)
            .toList();
        matching.forEach(dispatches::remove);
        return matching.size();
    }
}
