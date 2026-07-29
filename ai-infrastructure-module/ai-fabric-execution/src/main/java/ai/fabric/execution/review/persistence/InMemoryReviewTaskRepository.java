package ai.fabric.execution.review.persistence;

import ai.fabric.execution.review.ReviewTaskStatus;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryReviewTaskRepository
    implements ReviewTaskRepository {

    private final ConcurrentMap<String, ReviewTaskRecord> tasks =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idempotency =
        new ConcurrentHashMap<>();

    @Override
    public ReviewTaskRecord create(ReviewTaskRecord task) {
        String existingKey = idempotency.putIfAbsent(
            task.idempotencyFingerprint(),
            task.taskId()
        );
        if (existingKey != null) {
            throw new DuplicateTaskException(
                "Duplicate scoped review idempotency key",
                null
            );
        }
        if (tasks.putIfAbsent(task.taskId(), task) != null) {
            idempotency.remove(
                task.idempotencyFingerprint(),
                task.taskId()
            );
            throw new DuplicateTaskException(
                "Duplicate review task ID",
                null
            );
        }
        return task;
    }

    @Override
    public Optional<ReviewTaskRecord> findById(String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public Optional<ReviewTaskRecord> findByIdempotencyFingerprint(
        String fingerprint
    ) {
        String taskId = idempotency.get(fingerprint);
        return taskId == null ? Optional.empty() : findById(taskId);
    }

    @Override
    public boolean compareAndSet(
        ReviewTaskRecord expected,
        ReviewTaskRecord updated
    ) {
        validateTransition(expected, updated);
        return tasks.replace(expected.taskId(), expected, updated);
    }

    @Override
    public List<ReviewTaskRecord> findByTenantAndStatus(
        String tenantFingerprint,
        ReviewTaskStatus status,
        int limit
    ) {
        return tasks.values().stream()
            .filter(task ->
                task.tenantFingerprint().equals(tenantFingerprint)
                    && task.status() == status
            )
            .sorted(Comparator.comparing(ReviewTaskRecord::createdAt))
            .limit(positive(limit))
            .toList();
    }

    @Override
    public List<ReviewTaskRecord> findByStatus(
        ReviewTaskStatus status,
        int limit
    ) {
        return tasks.values().stream()
            .filter(task -> task.status() == status)
            .sorted(Comparator.comparing(ReviewTaskRecord::updatedAt))
            .limit(positive(limit))
            .toList();
    }

    @Override
    public List<ReviewTaskRecord> findRecoverable(
        Instant now,
        int limit
    ) {
        return tasks.values().stream()
            .filter(task ->
                task.status() == ReviewTaskStatus.DECIDING
                    && task.leaseUntil() != null
                    && !task.leaseUntil().isAfter(now)
            )
            .sorted(Comparator.comparing(ReviewTaskRecord::updatedAt))
            .limit(positive(limit))
            .toList();
    }

    @Override
    public List<ReviewTaskRecord> findExpiredWaiting(
        Instant now,
        int limit
    ) {
        return tasks.values().stream()
            .filter(task ->
                (task.status() == ReviewTaskStatus.WAITING_FOR_REVIEW
                    || task.status()
                        == ReviewTaskStatus.WAITING_FOR_INFORMATION)
                    && !task.expiresAt().isAfter(now)
            )
            .sorted(Comparator.comparing(ReviewTaskRecord::expiresAt))
            .limit(positive(limit))
            .toList();
    }

    @Override
    public List<ReviewTaskRecord> findTerminalBefore(
        Instant cutoff,
        int limit
    ) {
        return tasks.values().stream()
            .filter(task ->
                task.status().terminal()
                    && task.terminalAt() != null
                    && task.terminalAt().isBefore(cutoff)
            )
            .sorted(Comparator.comparing(ReviewTaskRecord::terminalAt))
            .limit(positive(limit))
            .toList();
    }

    @Override
    public boolean delete(ReviewTaskRecord expected) {
        boolean removed = tasks.remove(expected.taskId(), expected);
        if (removed) {
            idempotency.remove(
                expected.idempotencyFingerprint(),
                expected.taskId()
            );
        }
        return removed;
    }

    private void validateTransition(
        ReviewTaskRecord expected,
        ReviewTaskRecord updated
    ) {
        if (!expected.taskId().equals(updated.taskId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Task transition must preserve ID and increment version"
            );
        }
    }

    private long positive(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }
}
