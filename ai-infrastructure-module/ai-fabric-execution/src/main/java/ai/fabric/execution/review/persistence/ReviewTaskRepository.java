package ai.fabric.execution.review.persistence;

import ai.fabric.execution.review.ReviewTaskStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ReviewTaskRepository {

    ReviewTaskRecord create(ReviewTaskRecord task);

    Optional<ReviewTaskRecord> findById(String taskId);

    Optional<ReviewTaskRecord> findByIdempotencyFingerprint(
        String idempotencyFingerprint
    );

    boolean compareAndSet(
        ReviewTaskRecord expected,
        ReviewTaskRecord updated
    );

    List<ReviewTaskRecord> findByTenantAndStatus(
        String tenantFingerprint,
        ReviewTaskStatus status,
        int limit
    );

    List<ReviewTaskRecord> findByStatus(
        ReviewTaskStatus status,
        int limit
    );

    List<ReviewTaskRecord> findRecoverable(Instant now, int limit);

    List<ReviewTaskRecord> findExpiredWaiting(Instant now, int limit);

    List<ReviewTaskRecord> findTerminalBefore(Instant cutoff, int limit);

    boolean delete(ReviewTaskRecord expected);

    final class DuplicateTaskException extends RuntimeException {
        public DuplicateTaskException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
