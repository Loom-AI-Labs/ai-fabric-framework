package ai.fabric.execution.action;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ActionProposalReceiptRepository {

    ActionProposalReceipt create(ActionProposalReceipt receipt);

    Optional<ActionProposalReceipt> findById(String receiptId);

    Optional<ActionProposalReceipt> findByIdempotencyKey(String idempotencyKey);

    boolean compareAndSet(
        ActionProposalReceipt expected,
        ActionProposalReceipt updated
    );

    List<ActionProposalReceipt> findExpiredConfirmable(
        Instant now,
        int limit
    );

    List<ActionProposalReceipt> findUpdatedBefore(
        ActionProposalReceiptStatus status,
        Instant cutoff,
        int limit
    );

    List<ActionProposalReceipt> findRetainableTerminalBefore(
        Instant cutoff,
        int limit
    );

    boolean delete(ActionProposalReceipt expected);

    final class DuplicateReceiptException extends RuntimeException {
        public DuplicateReceiptException(String message, Throwable cause) {
            super(message, cause);
        }

        public DuplicateReceiptException(String message) {
            super(message);
        }
    }
}
