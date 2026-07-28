package ai.fabric.execution.action;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Deterministic receipt store for unit tests and explicitly selected local use.
 */
public final class InMemoryActionProposalReceiptRepository
    implements ActionProposalReceiptRepository {

    private final ConcurrentMap<String, ActionProposalReceipt> receipts =
        new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idempotencyKeys =
        new ConcurrentHashMap<>();

    @Override
    public ActionProposalReceipt create(ActionProposalReceipt receipt) {
        if (receipts.putIfAbsent(receipt.receiptId(), receipt) != null) {
            throw new DuplicateReceiptException("Duplicate receipt ID");
        }
        String existing = idempotencyKeys.putIfAbsent(
            receipt.idempotencyKey(),
            receipt.receiptId()
        );
        if (existing != null) {
            receipts.remove(receipt.receiptId(), receipt);
            throw new DuplicateReceiptException("Duplicate idempotency key");
        }
        return receipt;
    }

    @Override
    public Optional<ActionProposalReceipt> findById(String receiptId) {
        return Optional.ofNullable(receipts.get(receiptId));
    }

    @Override
    public Optional<ActionProposalReceipt> findByIdempotencyKey(
        String idempotencyKey
    ) {
        String receiptId = idempotencyKeys.get(idempotencyKey);
        return receiptId == null ? Optional.empty() : findById(receiptId);
    }

    @Override
    public boolean compareAndSet(
        ActionProposalReceipt expected,
        ActionProposalReceipt updated
    ) {
        if (!expected.receiptId().equals(updated.receiptId())
            || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException(
                "Receipt transition must preserve ID and increment version"
            );
        }
        return receipts.replace(expected.receiptId(), expected, updated);
    }

    @Override
    public List<ActionProposalReceipt> findExpiredConfirmable(
        Instant now,
        int limit
    ) {
        return receipts.values().stream()
            .filter(receipt ->
                (receipt.status() == ActionProposalReceiptStatus.PROPOSED
                    || receipt.status()
                        == ActionProposalReceiptStatus.CONFIRMED)
                    && !receipt.expiresAt().isAfter(now)
            )
            .sorted(Comparator.comparing(ActionProposalReceipt::expiresAt))
            .limit(positiveLimit(limit))
            .toList();
    }

    @Override
    public List<ActionProposalReceipt> findUpdatedBefore(
        ActionProposalReceiptStatus status,
        Instant cutoff,
        int limit
    ) {
        return receipts.values().stream()
            .filter(receipt ->
                receipt.status() == status
                    && receipt.updatedAt().isBefore(cutoff)
            )
            .sorted(Comparator.comparing(ActionProposalReceipt::updatedAt))
            .limit(positiveLimit(limit))
            .toList();
    }

    @Override
    public List<ActionProposalReceipt> findRetainableTerminalBefore(
        Instant cutoff,
        int limit
    ) {
        return receipts.values().stream()
            .filter(receipt ->
                retainableTerminal(receipt.status())
                    && receipt.terminalAt() != null
                    && receipt.terminalAt().isBefore(cutoff)
            )
            .sorted(Comparator.comparing(ActionProposalReceipt::terminalAt))
            .limit(positiveLimit(limit))
            .toList();
    }

    @Override
    public boolean delete(ActionProposalReceipt expected) {
        boolean removed = receipts.remove(expected.receiptId(), expected);
        if (removed) {
            idempotencyKeys.remove(
                expected.idempotencyKey(),
                expected.receiptId()
            );
        }
        return removed;
    }

    private boolean retainableTerminal(ActionProposalReceiptStatus status) {
        return status == ActionProposalReceiptStatus.SUCCEEDED
            || status == ActionProposalReceiptStatus.FAILED
            || status == ActionProposalReceiptStatus.REJECTED
            || status == ActionProposalReceiptStatus.EXPIRED;
    }

    private int positiveLimit(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        return limit;
    }
}
