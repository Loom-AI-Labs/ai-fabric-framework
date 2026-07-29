package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.state.DurableExecutionPayloadCodec;
import ai.fabric.execution.state.DurableExecutionRecord;
import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.execution.state.DurableExecutionSecurity;
import ai.fabric.execution.state.DurableExecutionSubmissionPolicy;
import ai.fabric.execution.state.DurableExecutionSubmissionPolicy.UnsupportedDurableExecutionException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Durable asynchronous boundary over the existing single-invocation executor.
 */
public final class DurableAIExecutionGateway implements AIExecutionGateway {

    private static final Logger log =
        LoggerFactory.getLogger(DurableAIExecutionGateway.class);

    private final AIExecutionGateway delegate;
    private final AssignedExecutionRunner runner;
    private final SpecialistRegistry specialistRegistry;
    private final DurableExecutionRepository repository;
    private final DurableExecutionPayloadCodec codec;
    private final DurableExecutionSecurity security;
    private final DurableExecutionSubmissionPolicy submissionPolicy;
    private final AsyncTaskExecutor taskExecutor;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Duration retention;
    private final int recoveryBatchSize;
    private final int maxAttempts;
    private final boolean cleanupEnabled;
    private final String workerId;

    public DurableAIExecutionGateway(
        AIExecutionGateway delegate,
        DefaultAIExecutionGateway runner,
        SpecialistRegistry specialistRegistry,
        DurableExecutionRepository repository,
        DurableExecutionPayloadCodec codec,
        DurableExecutionSecurity security,
        DurableExecutionSubmissionPolicy submissionPolicy,
        AsyncTaskExecutor taskExecutor,
        Clock clock,
        Duration leaseDuration,
        Duration retention,
        int recoveryBatchSize,
        int maxAttempts,
        boolean cleanupEnabled
    ) {
        this.delegate = Objects.requireNonNull(
            delegate,
            "delegate is required"
        );
        this.runner = Objects.requireNonNull(runner, "runner is required");
        this.specialistRegistry = Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.repository = Objects.requireNonNull(
            repository,
            "repository is required"
        );
        this.codec = Objects.requireNonNull(codec, "codec is required");
        this.security = Objects.requireNonNull(
            security,
            "security is required"
        );
        this.submissionPolicy = Objects.requireNonNull(
            submissionPolicy,
            "submissionPolicy is required"
        );
        this.taskExecutor = Objects.requireNonNull(
            taskExecutor,
            "taskExecutor is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.leaseDuration = positive(leaseDuration, "leaseDuration");
        this.retention = positive(retention, "retention");
        this.recoveryBatchSize = positive(
            recoveryBatchSize,
            "recoveryBatchSize"
        );
        this.maxAttempts = positive(maxAttempts, "maxAttempts");
        this.cleanupEnabled = cleanupEnabled;
        this.workerId = "ai-fabric-worker-" + UUID.randomUUID();
    }

    @Override
    public <I, O> AIExecutionResult<O> execute(
        AIExecutionRequest<I> request
    ) {
        return delegate.execute(request);
    }

    @Override
    public ExecutionHandle submit(AIExecutionRequest<?> request) {
        Objects.requireNonNull(request, "request is required");
        String invocationId = invocationId();
        RegisteredSpecialist specialist;
        Instant deadline;
        try {
            specialist = specialistRegistry.requireRegistered(
                request.specialistId()
            );
            deadline = runner.resolveDeadline(
                request,
                specialist.definition()
            );
            submissionPolicy.validate(request, specialist);
        } catch (UnsupportedDurableExecutionException ex) {
            return rejected(invocationId, request.deadline(), ex.reason());
        } catch (RuntimeException ex) {
            return rejected(
                invocationId,
                request.deadline(),
                "SPECIALIST_NOT_FOUND"
            );
        }

        String accessFingerprint;
        String idempotencyFingerprint;
        String requestFingerprint;
        String protectedRequest;
        try {
            accessFingerprint = security.accessFingerprint(
                request.trustedExecutionContext()
            );
            idempotencyFingerprint = request.idempotencyKey() == null
                ? null
                : security.idempotencyFingerprint(
                    request.trustedExecutionContext(),
                    request.idempotencyKey()
                );
            requestFingerprint = requestFingerprint(request);
            protectedRequest = codec.protectRequest(
                invocationId,
                withDeadline(request, deadline)
            );
        } catch (RuntimeException ex) {
            return rejected(
                invocationId,
                deadline,
                "EXECUTION_STATE_ENCODING_FAILED"
            );
        }

        ExecutionHandle replay = replayOrConflict(
            invocationId,
            idempotencyFingerprint,
            requestFingerprint,
            deadline
        );
        if (replay != null) {
            return replay;
        }

        Instant now = clock.instant();
        DurableExecutionRecord queued = DurableExecutionRecord.queued(
            invocationId,
            specialist.id(),
            specialist.contentHash(),
            accessFingerprint,
            idempotencyFingerprint,
            requestFingerprint,
            protectedRequest,
            deadline,
            now,
            retention
        );
        try {
            repository.create(queued);
        } catch (DurableExecutionRepository.DuplicateExecutionException ex) {
            ExecutionHandle raced = replayOrConflict(
                invocationId(),
                idempotencyFingerprint,
                requestFingerprint,
                deadline
            );
            return raced != null
                ? raced
                : rejected(
                    invocationId(),
                    deadline,
                    "IDEMPOTENCY_CONFLICT"
                );
        } catch (RuntimeException ex) {
            log.warn(
                "Durable execution state could not be persisted for {}",
                specialist.id()
            );
            return rejected(
                invocationId,
                deadline,
                "EXECUTION_STATE_UNAVAILABLE"
            );
        }

        dispatch(invocationId);
        return handle(queued);
    }

    @Override
    public <O> AIExecutionResumeResult<O> resume(
        AIExecutionResumeRequest request
    ) {
        return delegate.resume(request);
    }

    @Override
    public Optional<ExecutionSnapshot> find(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        if (invocationId == null || invocationId.isBlank()) {
            return Optional.empty();
        }
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        Optional<DurableExecutionRecord> durable = repository.findById(
            invocationId.trim()
        );
        if (durable.isPresent()) {
            if (!authorized(durable.get(), trustedExecutionContext)) {
                return Optional.empty();
            }
            return Optional.of(snapshot(durable.get()));
        }
        return delegate.find(invocationId, trustedExecutionContext);
    }

    @Override
    public boolean cancel(
        String invocationId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        if (invocationId != null && !invocationId.isBlank()) {
            for (int attempt = 0; attempt < 3; attempt++) {
                Optional<DurableExecutionRecord> found =
                    repository.findById(invocationId.trim());
                if (found.isEmpty()) {
                    break;
                }
                DurableExecutionRecord current = found.get();
                if (!authorized(current, trustedExecutionContext)) {
                    return false;
                }
                if (current.terminal()) {
                    return false;
                }
                DurableExecutionRecord cancelled = current.completed(
                    ExecutionHandleStatus.CANCELLED,
                    null,
                    "CANCELLED",
                    clock.instant(),
                    retention
                );
                if (repository.compareAndSet(current, cancelled)) {
                    return true;
                }
            }
        }
        return delegate.cancel(invocationId, trustedExecutionContext);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        recover();
    }

    @Scheduled(
        fixedDelayString =
            "${ai.execution.async.recovery-interval:PT30S}"
    )
    public RecoverySummary recover() {
        Instant now = clock.instant();
        int dispatched = 0;
        int deadlines = 0;
        int exhausted = 0;
        for (DurableExecutionRecord record :
            repository.findRecoverable(now, recoveryBatchSize)) {
            if (!now.isBefore(record.deadline())) {
                if (terminalize(
                    record,
                    ExecutionHandleStatus.EXPIRED,
                    "DEADLINE_EXCEEDED",
                    now
                )) {
                    deadlines++;
                }
                continue;
            }
            if (record.attemptCount() >= maxAttempts) {
                if (terminalize(
                    record,
                    ExecutionHandleStatus.FAILED,
                    "RECOVERY_ATTEMPTS_EXHAUSTED",
                    now
                )) {
                    exhausted++;
                }
                continue;
            }
            if (dispatch(record.invocationId())) {
                dispatched++;
            }
        }

        int deleted = 0;
        if (cleanupEnabled) {
            Instant cutoff = now.minus(retention);
            for (DurableExecutionRecord record :
                repository.findTerminalCompletedBefore(
                    cutoff,
                    recoveryBatchSize
                )) {
                if (repository.delete(record)) {
                    deleted++;
                }
            }
        }
        return new RecoverySummary(
            dispatched,
            deadlines,
            exhausted,
            deleted
        );
    }

    private boolean dispatch(String invocationId) {
        try {
            taskExecutor.submit(() -> run(invocationId));
            return true;
        } catch (RejectedExecutionException ex) {
            log.debug(
                "Durable execution {} remains queued for recovery",
                invocationId
            );
            return false;
        }
    }

    private void run(String invocationId) {
        DurableExecutionRecord claimed = claim(invocationId).orElse(null);
        if (claimed == null) {
            return;
        }
        AIExecutionResult<?> result;
        try {
            RegisteredSpecialist specialist =
                specialistRegistry.requireRegistered(
                    claimed.specialistId()
                );
            if (!security.sameFingerprint(
                specialist.contentHash(),
                claimed.specialistContentHash()
            )) {
                result = failure(
                    claimed,
                    "SPECIALIST_DEFINITION_CHANGED",
                    "The specialist definition changed before durable execution.",
                    false
                );
            } else {
                AIExecutionRequest<Object> request =
                    codec.unprotectRequest(claimed);
                result = supportedResult(
                    claimed,
                    runner.executeAssigned(
                        claimed.invocationId(),
                        request
                    )
                );
            }
        } catch (RuntimeException ex) {
            result = failure(
                claimed,
                "DURABLE_EXECUTION_FAILED",
                "The durable specialist execution failed.",
                false
            );
        }
        persistResult(claimed, result);
    }

    private Optional<DurableExecutionRecord> claim(String invocationId) {
        for (int attempt = 0; attempt < 3; attempt++) {
            Optional<DurableExecutionRecord> found =
                repository.findById(invocationId);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            DurableExecutionRecord current = found.get();
            Instant now = clock.instant();
            if (!current.claimable(now, maxAttempts)) {
                return Optional.empty();
            }
            DurableExecutionRecord claimed = current.claimed(
                workerId,
                now,
                now.plus(leaseDuration)
            );
            if (repository.compareAndSet(current, claimed)) {
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    private AIExecutionResult<?> supportedResult(
        DurableExecutionRecord record,
        AIExecutionResult<?> result
    ) {
        if (result.status() == AIExecutionStatus.WAITING_FOR_INPUT
            || result.status() == AIExecutionStatus.CONFIRMATION_REQUIRED) {
            return failure(
                record,
                "DURABLE_CONTINUATION_UNSUPPORTED",
                "Durable V1 jobs cannot wait for user input or confirmation.",
                false
            );
        }
        return result;
    }

    private void persistResult(
        DurableExecutionRecord claimed,
        AIExecutionResult<?> result
    ) {
        try {
            String protectedResult = codec.protectResult(claimed, result);
            ExecutionHandleStatus status = handleStatus(result);
            DurableExecutionRecord completed = claimed.completed(
                status,
                protectedResult,
                result.failure() != null
                    ? result.failure().reason()
                    : null,
                clock.instant(),
                retention
            );
            if (!repository.compareAndSet(claimed, completed)) {
                log.debug(
                    "Durable execution {} completed after its state changed",
                    claimed.invocationId()
                );
            }
        } catch (RuntimeException ex) {
            log.error(
                "Durable execution {} result could not be persisted",
                claimed.invocationId()
            );
        }
    }

    private boolean terminalize(
        DurableExecutionRecord current,
        ExecutionHandleStatus status,
        String reason,
        Instant now
    ) {
        AIExecutionResult<?> result = failure(
            current,
            reason,
            "The durable specialist execution could not be recovered.",
            false
        );
        String protectedResult;
        try {
            protectedResult = codec.protectResult(current, result);
        } catch (RuntimeException ex) {
            protectedResult = null;
        }
        return repository.compareAndSet(
            current,
            current.completed(
                status,
                protectedResult,
                reason,
                now,
                retention
            )
        );
    }

    private AIExecutionResult<?> failure(
        DurableExecutionRecord record,
        String reason,
        String message,
        boolean retryable
    ) {
        Instant now = clock.instant();
        return new AIExecutionResult<>(
            record.invocationId(),
            record.specialistId(),
            AIExecutionStatus.FAILED,
            null,
            java.util.List.of(),
            Map.of("durability", ExecutionDurability.DURABLE.name()),
            new AIExecutionFailure(reason, message, retryable),
            record.createdAt(),
            now
        );
    }

    private ExecutionSnapshot snapshot(DurableExecutionRecord record) {
        try {
            return new ExecutionSnapshot(
                handle(record),
                codec.unprotectResult(record)
            );
        } catch (RuntimeException ex) {
            AIExecutionResult<?> unavailable = failure(
                record,
                "EXECUTION_RESULT_UNAVAILABLE",
                "The durable execution result could not be verified.",
                false
            );
            return new ExecutionSnapshot(
                new ExecutionHandle(
                    record.invocationId(),
                    ExecutionDurability.DURABLE,
                    ExecutionHandleStatus.FAILED,
                    record.deadline(),
                    record.expiresAt(),
                    "EXECUTION_RESULT_UNAVAILABLE"
                ),
                unavailable
            );
        }
    }

    private ExecutionHandle replayOrConflict(
        String rejectedInvocationId,
        String idempotencyFingerprint,
        String requestFingerprint,
        Instant deadline
    ) {
        if (idempotencyFingerprint == null) {
            return null;
        }
        Optional<DurableExecutionRecord> existing =
            repository.findByIdempotencyFingerprint(
                idempotencyFingerprint
            );
        if (existing.isEmpty()) {
            return null;
        }
        return security.sameFingerprint(
            existing.get().requestFingerprint(),
            requestFingerprint
        )
            ? handle(existing.get())
            : rejected(
                rejectedInvocationId,
                deadline,
                "IDEMPOTENCY_CONFLICT"
            );
    }

    private boolean authorized(
        DurableExecutionRecord record,
        TrustedExecutionContext context
    ) {
        try {
            return security.sameFingerprint(
                record.accessFingerprint(),
                security.accessFingerprint(context)
            );
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String requestFingerprint(AIExecutionRequest<?> request) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("specialistId", request.specialistId().toString());
        canonical.put("input", request.input());
        canonical.put("conversationBinding", request.conversationBinding());
        return security.canonicalHash(canonical);
    }

    private AIExecutionRequest<?> withDeadline(
        AIExecutionRequest<?> request,
        Instant deadline
    ) {
        return new AIExecutionRequest<>(
            request.specialistId(),
            request.input(),
            request.trustedExecutionContext(),
            request.conversationBinding(),
            deadline,
            request.idempotencyKey()
        );
    }

    private ExecutionHandle handle(DurableExecutionRecord record) {
        return new ExecutionHandle(
            record.invocationId(),
            ExecutionDurability.DURABLE,
            record.status(),
            record.deadline(),
            record.expiresAt(),
            record.failureReason()
        );
    }

    private ExecutionHandle rejected(
        String invocationId,
        Instant deadline,
        String reason
    ) {
        Instant now = clock.instant();
        return new ExecutionHandle(
            invocationId,
            ExecutionDurability.DURABLE,
            ExecutionHandleStatus.REJECTED,
            deadline,
            now.plus(retention),
            reason
        );
    }

    private ExecutionHandleStatus handleStatus(
        AIExecutionResult<?> result
    ) {
        if (result.succeeded()) {
            return ExecutionHandleStatus.SUCCEEDED;
        }
        return result.status() == AIExecutionStatus.CANCELLED
            ? ExecutionHandleStatus.CANCELLED
            : ExecutionHandleStatus.FAILED;
    }

    private Duration positive(Duration value, String field) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private int positive(int value, String field) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    private String invocationId() {
        return "exec-" + UUID.randomUUID();
    }

    public record RecoverySummary(
        int dispatched,
        int deadlineFailures,
        int attemptFailures,
        int deletedAfterRetention
    ) {}
}
