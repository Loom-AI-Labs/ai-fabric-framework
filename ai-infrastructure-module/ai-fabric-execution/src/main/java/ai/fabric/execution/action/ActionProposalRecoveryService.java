package ai.fabric.execution.action;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Expires stale proposals and marks abandoned executions unknown without retrying them.
 */
public final class ActionProposalRecoveryService {

    private final ActionProposalReceiptRepository repository;
    private final ActionProposalSecurity security;
    private final ActionProposalMetrics metrics;
    private final Clock clock;
    private final Duration staleExecutingAfter;
    private final int batchSize;
    private final boolean cleanupEnabled;
    private final Duration retention;

    public ActionProposalRecoveryService(
        ActionProposalReceiptRepository repository,
        ActionProposalSecurity security,
        ActionProposalMetrics metrics,
        Clock clock,
        Duration staleExecutingAfter,
        int batchSize
    ) {
        this(
            repository,
            security,
            metrics,
            clock,
            staleExecutingAfter,
            batchSize,
            false,
            Duration.ofDays(90)
        );
    }

    public ActionProposalRecoveryService(
        ActionProposalReceiptRepository repository,
        ActionProposalSecurity security,
        ActionProposalMetrics metrics,
        Clock clock,
        Duration staleExecutingAfter,
        int batchSize,
        boolean cleanupEnabled,
        Duration retention
    ) {
        this.repository = Objects.requireNonNull(
            repository,
            "repository is required"
        );
        this.security = Objects.requireNonNull(security, "security is required");
        this.metrics = metrics != null ? metrics : ActionProposalMetrics.noop();
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (staleExecutingAfter == null
            || staleExecutingAfter.isZero()
            || staleExecutingAfter.isNegative()) {
            throw new IllegalArgumentException(
                "staleExecutingAfter must be positive"
            );
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("batchSize must be positive");
        }
        if (retention == null
            || retention.isZero()
            || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
        this.staleExecutingAfter = staleExecutingAfter;
        this.batchSize = batchSize;
        this.cleanupEnabled = cleanupEnabled;
        this.retention = retention;
    }

    @PostConstruct
    public void recoverAtStartup() {
        recover();
    }

    @Scheduled(
        fixedDelayString =
            "${ai.execution.receipts.recovery-interval:PT1M}"
    )
    public RecoverySummary recover() {
        Instant now = clock.instant();
        int expired = 0;
        for (ActionProposalReceipt receipt :
            repository.findExpiredConfirmable(now, batchSize)) {
            ActionProposalReceipt next = receipt.expired(now);
            if (repository.compareAndSet(receipt, next)) {
                expired++;
                metrics.record("expired", next.actionName(), next.status());
            }
        }

        int unknown = 0;
        Instant cutoff = now.minus(staleExecutingAfter);
        for (ActionProposalReceipt receipt : repository.findUpdatedBefore(
            ActionProposalReceiptStatus.EXECUTING,
            cutoff,
            batchSize
        )) {
            ActionOutcomeView outcome = new ActionOutcomeView(
                receipt.actionName(),
                "The action may have completed, but its authoritative outcome is not yet known.",
                java.util.Map.of("requiresReconciliation", true)
            );
            String protectedOutcome = security.protect(
                java.util.Map.of(
                    "actionName",
                    outcome.actionName(),
                    "message",
                    outcome.message(),
                    "data",
                    outcome.data()
                ),
                receipt.receiptId() + ":outcome"
            );
            ActionProposalReceipt next = receipt.unknownAfterRecovery(
                now,
                protectedOutcome
            );
            if (repository.compareAndSet(receipt, next)) {
                unknown++;
                metrics.record(
                    "recovered_unknown",
                    next.actionName(),
                    next.status()
                );
            }
        }
        int cleaned = 0;
        if (cleanupEnabled) {
            Instant retentionCutoff = now.minus(retention);
            for (ActionProposalReceipt receipt :
                repository.findRetainableTerminalBefore(
                    retentionCutoff,
                    batchSize
                )) {
                if (repository.delete(receipt)) {
                    cleaned++;
                    metrics.record(
                        "retention_deleted",
                        receipt.actionName(),
                        receipt.status()
                    );
                }
            }
        }
        return new RecoverySummary(expired, unknown, cleaned);
    }

    public record RecoverySummary(
        int expiredProposals,
        int unknownExecutions,
        int deletedAfterRetention
    ) {}
}
