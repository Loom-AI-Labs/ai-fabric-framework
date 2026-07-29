package ai.fabric.execution.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Optimistic durable-state boundary for asynchronous specialist jobs.
 */
public interface DurableExecutionRepository {

    DurableExecutionRecord create(DurableExecutionRecord record);

    Optional<DurableExecutionRecord> findById(String invocationId);

    Optional<DurableExecutionRecord> findByIdempotencyFingerprint(
        String idempotencyFingerprint
    );

    boolean compareAndSet(
        DurableExecutionRecord expected,
        DurableExecutionRecord updated
    );

    List<DurableExecutionRecord> findRecoverable(Instant now, int limit);

    List<DurableExecutionRecord> findTerminalCompletedBefore(
        Instant cutoff,
        int limit
    );

    boolean delete(DurableExecutionRecord expected);

    final class DuplicateExecutionException extends RuntimeException {
        public DuplicateExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
