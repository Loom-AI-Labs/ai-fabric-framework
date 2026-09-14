package ai.fabric.execution.chain.state;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Optimistic persistence boundary for specialist-chain checkpoints. */
public interface SpecialistChainExecutionRepository {

    SpecialistChainExecutionRecord create(
        SpecialistChainExecutionRecord record
    );

    Optional<SpecialistChainExecutionRecord> findById(String executionId);

    Optional<SpecialistChainExecutionRecord> findByIdempotencyFingerprint(
        String idempotencyFingerprint
    );

    boolean compareAndSet(
        SpecialistChainExecutionRecord expected,
        SpecialistChainExecutionRecord updated
    );

    List<SpecialistChainExecutionRecord> findRecoverable(
        Instant now,
        int limit
    );

    List<SpecialistChainExecutionRecord> findTerminalCompletedBefore(
        Instant cutoff,
        int limit
    );

    long countActive();

    boolean delete(SpecialistChainExecutionRecord expected);

    final class DuplicateChainExecutionException extends RuntimeException {
        public DuplicateChainExecutionException(
            String message,
            Throwable cause
        ) {
            super(message, cause);
        }

        public DuplicateChainExecutionException(String message) {
            super(message);
        }
    }
}
