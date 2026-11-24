package ai.fabric.execution.gateway;

import ai.fabric.execution.context.ExecutionSource;
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
 * Durable asynchronous boundary for machine-triggered submissions.
 *
 * <p>Interactive submissions remain ephemeral because their approved
 * conversation snapshot is intentionally process-local and single-use.
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
    private DurableGoalExecutionHost goalHost;

    public void setDurableGoalExecutionHost(DurableGoalExecutionHost host) { this.goalHost = host; }

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
        return submitPrepared(request, null);
    }

    @Override public ExecutionHandle submitGoal(GoalExecutionRequest goal) {
        Objects.requireNonNull(goal);
        if (goalHost == null) return rejected(invocationId(), goal.request().deadline(), "DURABLE_GOAL_HOST_UNAVAILABLE");
        try {
            var prepared = Objects.requireNonNull(goalHost.prepareSubmission(goal));
            if (!sameIdentity(goal.request(), prepared) || prepared.conversationBinding() != null
                || prepared.trustedExecutionContext().subject() == null)
                return rejected(invocationId(), goal.request().deadline(), "DURABLE_GOAL_AUTHORITY_INVALID");
            return submitPrepared(prepared, goal.authorityReference());
        } catch (RuntimeException denied) {
            return rejected(invocationId(), goal.request().deadline(), "DURABLE_GOAL_AUTHORITY_INVALID");
        }
    }

    private ExecutionHandle submitPrepared(AIExecutionRequest<?> request, String goalAuthority) {
        Objects.requireNonNull(request, "request is required");
        if (
            goalAuthority == null && request.trustedExecutionContext().source()
            == ExecutionSource.INTERACTIVE
        ) {
            return delegate.submit(request);
        }
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
            // Persist a narrowing precision shared by JDBC TIMESTAMP implementations.
            if (goalAuthority != null) deadline = deadline.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            if (goalAuthority == null) submissionPolicy.validate(request, specialist);
            else if (!specialist.definition().executionProfile().workflowPolicy().goalMode())
                throw new UnsupportedDurableExecutionException("WORKFLOW_GOAL_DISABLED", "The published profile does not enable goals.");
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
            requestFingerprint = requestFingerprint(request, goalAuthority);
            protectedRequest = codec.protectRequest(
                invocationId,
                withDeadline(request, deadline), goalAuthority
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
        if (goalAuthority != null) queued = queued.withGoalAuthority(goalAuthority);
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

    @Override public boolean controlGoal(String invocationId, GoalExecutionControl command, TrustedExecutionContext context) {
        Objects.requireNonNull(command); Objects.requireNonNull(context);
        for (int attempt = 0; attempt < 3; attempt++) {
            var current = repository.findById(invocationId).orElse(null);
            if (current == null || !current.goalExecution() || current.terminal() || !authorized(current, context)) return false;
            DurableExecutionRecord next;
            if (command == GoalExecutionControl.CANCEL || command == GoalExecutionControl.PAUSE) {
                boolean cancelled = command == GoalExecutionControl.CANCEL;
                String reason = cancelled ? "CANCELLED" : "GOAL_USER_PAUSED";
                String protectedCheckpoint = current.protectedWorkflowCheckpoint();
                var checkpoint = codec.unprotectWorkflowCheckpoint(current).orElse(null);
                Map<String, Object> diagnostics = Map.of();
                if (checkpoint != null) {
                    var state = cancelled ? ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.State.STOPPED
                        : ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.State.PAUSED;
                    var controlled = new ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint(checkpoint.workId(), checkpoint.requestFingerprint(),
                        checkpoint.authorityFingerprint(), checkpoint.definitionFingerprint(), checkpoint.deadline(), checkpoint.version() + 1,
                        checkpoint.usage(), checkpoint.steps(), state, checkpoint.message(), checkpoint.questions(), reason, checkpoint.goal());
                    protectedCheckpoint = codec.protectWorkflowCheckpoint(controlled);
                    diagnostics = Map.of("workflow", Map.of("state", state.name(), "usage", checkpoint.usage(), "stopCode", reason));
                }
                var result = new AIExecutionResult<>(current.invocationId(), current.specialistId(), cancelled ? AIExecutionStatus.CANCELLED : AIExecutionStatus.PAUSED,
                    null, java.util.List.of(), diagnostics, new AIExecutionFailure(reason, cancelled ? "Goal cancelled." : "Goal paused.", !cancelled),
                    current.createdAt(), clock.instant());
                next = current.controlled(cancelled ? ExecutionHandleStatus.CANCELLED : ExecutionHandleStatus.PAUSED,
                    codec.protectResult(current, result), protectedCheckpoint, reason, clock.instant(), retention);
            }
            else {
                if (current.status() != ExecutionHandleStatus.PAUSED || !clock.instant().isBefore(current.deadline())) return false;
                next = current.controlled(ExecutionHandleStatus.QUEUED, current.protectedResult(), current.protectedWorkflowCheckpoint(),
                    "GOAL_RESUME_REQUESTED", clock.instant(), retention);
            }
            if (repository.compareAndSet(current, next)) {
                notifyHost(next);
                if (command == GoalExecutionControl.RESUME) dispatch(invocationId);
                return true;
            }
        }
        return false;
    }

    @Override public Optional<ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint> findWorkflowCheckpoint(
        String invocationId, TrustedExecutionContext context) {
        return repository.findById(invocationId).filter(record -> authorized(record, context))
            .flatMap(codec::unprotectWorkflowCheckpoint);
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
                if (current.goalExecution()) return controlGoal(invocationId, GoalExecutionControl.CANCEL, trustedExecutionContext);
                DurableExecutionRecord cancelled = current.completed(
                    ExecutionHandleStatus.CANCELLED,
                    null,
                    "CANCELLED",
                    clock.instant(),
                    retention
                );
                if (repository.compareAndSet(current, cancelled)) {
                    notifyHost(cancelled);
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
                if (record.goalExecution()) {
                    repository.compareAndSet(record, record.goalTransition(ExecutionHandleStatus.PAUSED, record.protectedResult(),
                        "WORKFLOW_GOAL_DEADLINE", null, now, false));
                    continue;
                }
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
                if (record.goalExecution()) {
                    repository.compareAndSet(record, record.goalTransition(ExecutionHandleStatus.PAUSED, record.protectedResult(),
                        "RECOVERY_ATTEMPTS_EXHAUSTED", null, now, false));
                    continue;
                }
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
        for (var record : repository.findGoalNotifications(now, recoveryBatchSize)) notifyHost(record);

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
            RegisteredSpecialist specialist;
            try { specialist = specialistRegistry.requireRegistered(claimed.specialistId()); }
            catch (RuntimeException unavailable) {
                if (!claimed.goalExecution()) throw unavailable;
                persistResult(claimed, paused(claimed, "SPECIALIST_DEFINITION_UNAVAILABLE"));
                return;
            }
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
                if (claimed.goalExecution()) result = paused(claimed, "SPECIALIST_DEFINITION_CHANGED");
            } else {
                AIExecutionRequest<Object> request =
                    codec.unprotectRequest(claimed);
                if (claimed.goalExecution()) {
                    if (goalHost == null) result = paused(claimed, "DURABLE_GOAL_HOST_UNAVAILABLE");
                    else {
                        var claim = new GoalExecutionClaim(claimed.invocationId(), claimed.goalAuthorityReference(),
                            claimed.leaseOwner(), claimed.deadline(), claimed.attemptCount(), "GOAL_RESUME_REQUESTED".equals(claimed.failureReason()));
                        result = goalHost.executeSegment(claim, request, restored -> {
                            if (!sameIdentity(request, restored) || !security.sameFingerprint(security.canonicalHash(request.input()),
                                security.canonicalHash(restored.input())) || restored.deadline() == null || restored.deadline().isAfter(claimed.deadline()))
                                throw new SecurityException("DURABLE_GOAL_RESTORATION_CONFLICT");
                            return runner.executeAssigned(claimed.invocationId(), restored);
                        });
                    }
                } else result = supportedResult(claimed, runner.executeAssigned(claimed.invocationId(), request));
            }
        } catch (RuntimeException ex) {
            result = claimed.goalExecution() ? (ex instanceof SecurityException
                ? new AIExecutionResult<>(claimed.invocationId(), claimed.specialistId(), AIExecutionStatus.DENIED, null,
                    java.util.List.of(), Map.of(), new AIExecutionFailure("DURABLE_GOAL_AUTHORITY_DENIED", "Goal authority was denied.", false),
                    claimed.createdAt(), clock.instant())
                : paused(claimed, ex instanceof ai.fabric.intent.orchestration.workflow.WorkflowAllowance.Stop stop
                    ? stop.code() : "DURABLE_GOAL_HOST_UNAVAILABLE")) : failure(
                claimed,
                "DURABLE_EXECUTION_FAILED",
                "The durable specialist execution failed.",
                false
            );
        }
        persistResult(claimed, result);
    }

    private boolean sameIdentity(AIExecutionRequest<?> original, AIExecutionRequest<?> restored) {
        var a = original.trustedExecutionContext(); var b = restored.trustedExecutionContext();
        return original.specialistId().equals(restored.specialistId()) && a.initiator().equals(b.initiator())
            && Objects.equals(a.subject(), b.subject()) && a.source() == b.source()
            && Objects.equals(a.tenantId(), b.tenantId()) && Objects.equals(a.deploymentId(), b.deploymentId())
            && a.grantedScopes().equals(b.grantedScopes());
    }

    private AIExecutionResult<?> paused(DurableExecutionRecord record, String code) {
        return new AIExecutionResult<>(record.invocationId(), record.specialistId(), AIExecutionStatus.PAUSED, null,
            java.util.List.of(), Map.of(), new AIExecutionFailure(code, "Goal execution requires host attention.", true),
            record.createdAt(), clock.instant());
    }

    private void notifyHost(DurableExecutionRecord record) {
        if (!record.goalNotificationPending() || goalHost == null) return;
        try {
            goalHost.executionUpdated(snapshot(record), record.goalAuthorityReference());
            repository.compareAndSet(record, record.notificationDelivered());
        } catch (RuntimeException unavailable) {
            log.warn("Goal {} host notification remains pending", record.invocationId());
        }
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
                now.plus(current.goalExecution() ? goalLeaseDuration(current) : leaseDuration)
            );
            if (repository.compareAndSet(current, claimed)) {
                return Optional.of(claimed);
            }
        }
        return Optional.empty();
    }

    private Duration goalLeaseDuration(DurableExecutionRecord record) {
        try {
            var segment = specialistRegistry.requireRegistered(record.specialistId()).definition()
                .executionProfile().workflowPolicy().timeout().plusSeconds(30);
            return segment.compareTo(leaseDuration) > 0 ? segment : leaseDuration;
        } catch (RuntimeException retired) { return leaseDuration; }
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
            // Step checkpoints can advance the record while it runs. Do not overwrite
            // a newer worker, cancellation or terminal result when committing output.
            DurableExecutionRecord latest = repository.findById(claimed.invocationId()).orElse(null);
            if (latest == null || latest.status() != ExecutionHandleStatus.RUNNING
                || !Objects.equals(latest.leaseOwner(), claimed.leaseOwner())
                || latest.attemptCount() != claimed.attemptCount() || latest.leaseUntil() == null
                || !clock.instant().isBefore(latest.leaseUntil())) return;
            claimed = latest;
            String protectedResult = codec.protectResult(claimed, result);
            if (claimed.goalExecution() && (result.status() == AIExecutionStatus.YIELDED || result.status() == AIExecutionStatus.PAUSED
                || result.status() == AIExecutionStatus.WAITING_FOR_INPUT || result.status() == AIExecutionStatus.CONFIRMATION_REQUIRED)) {
                boolean yielded = result.status() == AIExecutionStatus.YIELDED;
                Instant eligibleAt = clock.instant();
                if (yielded && result.diagnostics().get("workflow") instanceof Map<?, ?> workflow
                    && workflow.get("nextEligibleAt") instanceof String value) eligibleAt = Instant.parse(value);
                String reason = result.failure() == null ? result.status().name() : result.failure().reason();
                if (result.failure() == null && result.diagnostics().get("workflow") instanceof Map<?, ?> workflow
                    && workflow.get("stopCode") instanceof String code) reason = code;
                else if (result.failure() == null && result.diagnostics().get("workflow") instanceof Map<?, ?> workflow
                    && "NEEDS_INPUT".equals(workflow.get("state"))) reason = "NEEDS_INPUT";
                var next = claimed.goalTransition(yielded ? ExecutionHandleStatus.YIELDED : ExecutionHandleStatus.PAUSED,
                    protectedResult, reason, yielded ? eligibleAt : null, clock.instant(), true);
                if (repository.compareAndSet(claimed, next)) notifyHost(next);
                return;
            }
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
            } else notifyHost(completed);
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

    private String requestFingerprint(AIExecutionRequest<?> request, String goalAuthority) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("specialistId", request.specialistId().toString());
        canonical.put("input", request.input());
        canonical.put("conversationBinding", request.conversationBinding());
        if (goalAuthority != null) canonical.put("goalAuthority", goalAuthority);
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
