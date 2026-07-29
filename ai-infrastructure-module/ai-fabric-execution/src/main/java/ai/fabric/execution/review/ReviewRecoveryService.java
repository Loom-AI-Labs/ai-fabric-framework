package ai.fabric.execution.review;

import ai.fabric.execution.review.persistence.ReviewDispatchRepository;
import ai.fabric.execution.review.persistence.ReviewTaskRecord;
import ai.fabric.execution.review.persistence.ReviewTaskRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Recovers expired decision leases, expires untouched tasks, and optionally
 * removes retained terminal tasks.
 */
public final class ReviewRecoveryService {

    private final ReviewTaskRepository repository;
    private final ReviewDispatchRepository dispatchRepository;
    private final ReviewDecisionGateway gateway;
    private final Clock clock;
    private final int batchSize;
    private final boolean cleanupEnabled;
    private final Duration retention;

    public ReviewRecoveryService(
        ReviewTaskRepository repository,
        ReviewDispatchRepository dispatchRepository,
        ReviewDecisionGateway gateway,
        Clock clock,
        int batchSize,
        boolean cleanupEnabled,
        Duration retention
    ) {
        this.repository = Objects.requireNonNull(
            repository,
            "repository is required"
        );
        this.dispatchRepository = Objects.requireNonNull(
            dispatchRepository,
            "dispatchRepository is required"
        );
        this.gateway = Objects.requireNonNull(
            gateway,
            "gateway is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                "batchSize must be positive"
            );
        }
        if (retention == null
            || retention.isZero()
            || retention.isNegative()) {
            throw new IllegalArgumentException("retention must be positive");
        }
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
            "${ai.execution.reviews.recovery-interval:PT30S}"
    )
    public RecoverySummary recover() {
        Instant now = clock.instant();
        int expired = 0;
        for (ReviewTaskRecord task :
            repository.findExpiredWaiting(now, batchSize)) {
            if (repository.compareAndSet(task, task.expired(now))) {
                expired++;
            }
        }

        int redispatched = 0;
        for (ReviewTaskRecord task :
            repository.findByStatus(
                ai.fabric.execution.review.ReviewTaskStatus
                    .WAITING_FOR_REVIEW,
                batchSize
            )) {
            ReviewDecisionGateway.DispatchRecoveryResult result =
                gateway.recoverDispatch(task);
            if (result.attempted() && result.accepted()) {
                redispatched++;
            }
        }

        int recovered = 0;
        for (ReviewTaskRecord task :
            repository.findRecoverable(now, batchSize)) {
            gateway.recover(
                task,
                "review-recovery-" + UUID.randomUUID()
            );
            recovered++;
        }

        int deleted = 0;
        if (cleanupEnabled) {
            Instant cutoff = now.minus(retention);
            for (ReviewTaskRecord task :
                repository.findTerminalBefore(cutoff, batchSize)) {
                dispatchRepository.deleteByTaskId(task.taskId());
                if (repository.delete(task)) {
                    deleted++;
                }
            }
        }
        return new RecoverySummary(
            expired,
            redispatched,
            recovered,
            deleted
        );
    }

    public record RecoverySummary(
        int expiredTasks,
        int recoveredDispatches,
        int recoveredDecisions,
        int deletedAfterRetention
    ) {}
}
