package ai.fabric.execution.gateway;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * In-memory execution state. Entries intentionally do not survive process restart.
 */
final class EphemeralExecutionStore {

    private final Clock clock;
    private final Duration ttl;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys = new ConcurrentHashMap<>();

    EphemeralExecutionStore(Clock clock, Duration ttl) {
        this.clock = java.util.Objects.requireNonNull(clock, "clock is required");
        this.ttl = java.util.Objects.requireNonNull(ttl, "ttl is required");
    }

    Optional<String> invocationForIdempotencyKey(String key) {
        cleanup();
        return key == null ? Optional.empty() : Optional.ofNullable(idempotencyKeys.get(key));
    }

    Entry create(
        String invocationId,
        String idempotencyKey,
        Instant deadline,
        ExecutionHandleStatus status,
        String failureReason
    ) {
        cleanup();
        Instant now = clock.instant();
        Instant retentionAnchor = deadline != null && deadline.isAfter(now)
            ? deadline
            : now;
        Entry entry = new Entry(
            invocationId,
            idempotencyKey,
            deadline,
            retentionAnchor.plus(ttl),
            status,
            failureReason
        );
        if (entries.putIfAbsent(invocationId, entry) != null) {
            throw new IllegalStateException("Duplicate invocation ID " + invocationId);
        }
        if (idempotencyKey != null) {
            String existing = idempotencyKeys.putIfAbsent(idempotencyKey, invocationId);
            if (existing != null) {
                entries.remove(invocationId);
                throw new DuplicateIdempotencyKeyException(existing);
            }
        }
        return entry;
    }

    Optional<Entry> find(String invocationId) {
        cleanup();
        return Optional.ofNullable(entries.get(invocationId));
    }

    boolean markRunning(Entry entry) {
        synchronized (entry) {
            if (entry.status != ExecutionHandleStatus.QUEUED) {
                return false;
            }
            entry.status = ExecutionHandleStatus.RUNNING;
            entry.failureReason = null;
            return true;
        }
    }

    void complete(Entry entry, AIExecutionResult<?> result) {
        ExecutionHandleStatus status = result.succeeded()
            ? ExecutionHandleStatus.SUCCEEDED
            : result.status() == AIExecutionStatus.CANCELLED
                ? ExecutionHandleStatus.CANCELLED
                : ExecutionHandleStatus.FAILED;
        entry.update(
            status,
            result,
            result.failure() != null ? result.failure().reason() : null
        );
        entry.expiresAt = clock.instant().plus(ttl);
    }

    void reject(Entry entry, String reason) {
        entry.update(ExecutionHandleStatus.REJECTED, null, reason);
        entry.expiresAt = clock.instant().plus(ttl);
    }

    void attachFuture(Entry entry, Future<?> future) {
        entry.future = future;
    }

    boolean cancel(Entry entry) {
        synchronized (entry) {
            if (entry.status != ExecutionHandleStatus.QUEUED
                && entry.status != ExecutionHandleStatus.RUNNING) {
                return false;
            }
            boolean cancelled = entry.future == null || entry.future.cancel(true);
            if (cancelled) {
                entry.status = ExecutionHandleStatus.CANCELLED;
                entry.failureReason = "CANCELLED";
                entry.expiresAt = clock.instant().plus(ttl);
            }
            return cancelled;
        }
    }

    ExecutionSnapshot snapshot(Entry entry) {
        synchronized (entry) {
            return new ExecutionSnapshot(
                new ExecutionHandle(
                    entry.invocationId,
                    ExecutionDurability.EPHEMERAL,
                    entry.status,
                    entry.deadline,
                    entry.expiresAt,
                    entry.failureReason
                ),
                entry.result
            );
        }
    }

    private void cleanup() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> {
            entry.failWhenDeadlineExceeded(now, ttl);
            if (!entry.isTerminal()) {
                return false;
            }
            if (!now.isAfter(entry.expiresAt)) {
                return false;
            }
            if (entry.idempotencyKey != null) {
                idempotencyKeys.remove(entry.idempotencyKey, entry.invocationId);
            }
            return true;
        });
    }

    static final class Entry {
        private final String invocationId;
        private final String idempotencyKey;
        private final Instant deadline;
        private volatile Instant expiresAt;
        private volatile ExecutionHandleStatus status;
        private volatile AIExecutionResult<?> result;
        private volatile String failureReason;
        private volatile Future<?> future;

        private Entry(
            String invocationId,
            String idempotencyKey,
            Instant deadline,
            Instant expiresAt,
            ExecutionHandleStatus status,
            String failureReason
        ) {
            this.invocationId = invocationId;
            this.idempotencyKey = idempotencyKey;
            this.deadline = deadline;
            this.expiresAt = expiresAt;
            this.status = status;
            this.failureReason = failureReason;
        }

        private synchronized void update(
            ExecutionHandleStatus status,
            AIExecutionResult<?> result,
            String failureReason
        ) {
            if (isTerminal()) {
                return;
            }
            this.status = status;
            this.result = result;
            this.failureReason = failureReason;
        }

        private synchronized void failWhenDeadlineExceeded(
            Instant now,
            Duration ttl
        ) {
            if (isTerminal()) {
                return;
            }
            if (deadline == null) {
                if (now.isAfter(expiresAt)) {
                    status = ExecutionHandleStatus.EXPIRED;
                    failureReason = "EXPIRED";
                }
                return;
            }
            if (now.isBefore(deadline)) {
                return;
            }
            if (future != null) {
                future.cancel(true);
            }
            status = ExecutionHandleStatus.FAILED;
            failureReason = "DEADLINE_EXCEEDED";
            expiresAt = now.plus(ttl);
        }

        private boolean isTerminal() {
            return status == ExecutionHandleStatus.SUCCEEDED
                || status == ExecutionHandleStatus.FAILED
                || status == ExecutionHandleStatus.CANCELLED
                || status == ExecutionHandleStatus.REJECTED
                || status == ExecutionHandleStatus.EXPIRED;
        }
    }

    static final class DuplicateIdempotencyKeyException extends RuntimeException {
        private final String existingInvocationId;

        DuplicateIdempotencyKeyException(String existingInvocationId) {
            super("Duplicate live idempotency key");
            this.existingInvocationId = existingInvocationId;
        }

        String existingInvocationId() {
            return existingInvocationId;
        }
    }
}
