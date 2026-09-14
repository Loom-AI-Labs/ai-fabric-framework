package ai.fabric.execution.gateway;

import ai.fabric.evidence.AIEvidenceReference;
import ai.fabric.execution.chain.RegisteredSpecialistChain;
import ai.fabric.execution.chain.SpecialistChainBudgetView;
import ai.fabric.execution.chain.SpecialistChainConversationPolicy;
import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainDirective;
import ai.fabric.execution.chain.SpecialistChainDirectiveType;
import ai.fabric.execution.chain.SpecialistChainExecutionHandle;
import ai.fabric.execution.chain.SpecialistChainExecutionRequest;
import ai.fabric.execution.chain.SpecialistChainExecutionResult;
import ai.fabric.execution.chain.SpecialistChainExecutionSnapshot;
import ai.fabric.execution.chain.SpecialistChainExecutionStatus;
import ai.fabric.execution.chain.SpecialistChainFailure;
import ai.fabric.execution.chain.SpecialistChainGateway;
import ai.fabric.execution.chain.SpecialistChainManagerInput;
import ai.fabric.execution.chain.SpecialistChainMetrics;
import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.chain.SpecialistChainResultProjection;
import ai.fabric.execution.chain.SpecialistChainResultView;
import ai.fabric.execution.chain.SpecialistChainStepTrace;
import ai.fabric.execution.chain.SpecialistChainTarget;
import ai.fabric.execution.chain.SpecialistChainTargetRequest;
import ai.fabric.execution.chain.SpecialistChainWorkerStatus;
import ai.fabric.execution.chain.SpecialistChainWorkerTrace;
import ai.fabric.execution.chain.state.SpecialistChainCheckpoint;
import ai.fabric.execution.chain.state.SpecialistChainCheckpointPhase;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRecord;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainManagerExecutionCheckpoint;
import ai.fabric.execution.chain.state.SpecialistChainPayloadCodec;
import ai.fabric.execution.chain.state.SpecialistChainSecurity;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.delegation.SpecialistDelegationRequest;
import ai.fabric.execution.delegation.SpecialistDelegationResult;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.handoff.SpecialistHandoffRequest;
import ai.fabric.execution.handoff.SpecialistHandoffResult;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Centralized bounded manager loop over existing one-level specialist
 * transitions.
 */
public final class DefaultSpecialistChainGateway
    implements SpecialistChainGateway {

    private static final Logger log = LoggerFactory.getLogger(
        DefaultSpecialistChainGateway.class
    );
    private static final Duration POLL_INTERVAL = Duration.ofMillis(15);

    private final SpecialistChainRegistry chainRegistry;
    private final SpecialistClientFactory clientFactory;
    private final SpecialistDelegationGateway delegationGateway;
    private final SpecialistHandoffGateway handoffGateway;
    private final AIExecutionConversationRecorder conversationRecorder;
    private final SharedInteractiveTurnCoordinator turnCoordinator;
    private final SpecialistChainExecutionRepository repository;
    private final SpecialistChainPayloadCodec codec;
    private final SpecialistChainSecurity security;
    private final AsyncTaskExecutor taskExecutor;
    private final CanonicalJsonSupport canonicalJson;
    private final Clock clock;
    private final SpecialistChainMetrics metrics;
    private final boolean durable;
    private final int maxActive;
    private final Duration leaseDuration;
    private final Duration retention;
    private final int recoveryBatchSize;
    private final int maxAttempts;
    private final int maxDirectiveCorrections;
    private final boolean cleanupEnabled;
    private final String workerId;
    private final ConcurrentMap<String, ActiveExecution> activeExecutions =
        new ConcurrentHashMap<>();

    public DefaultSpecialistChainGateway(
        SpecialistChainRegistry chainRegistry,
        SpecialistClientFactory clientFactory,
        SpecialistDelegationGateway delegationGateway,
        SpecialistHandoffGateway handoffGateway,
        AIExecutionConversationRecorder conversationRecorder,
        SharedInteractiveTurnCoordinator turnCoordinator,
        SpecialistChainExecutionRepository repository,
        SpecialistChainPayloadCodec codec,
        SpecialistChainSecurity security,
        AsyncTaskExecutor taskExecutor,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        SpecialistChainMetrics metrics,
        AIExecutionProperties.SpecialistChains properties,
        boolean durable
    ) {
        this.chainRegistry = Objects.requireNonNull(
            chainRegistry,
            "chainRegistry is required"
        );
        this.clientFactory = Objects.requireNonNull(
            clientFactory,
            "clientFactory is required"
        );
        this.delegationGateway = Objects.requireNonNull(
            delegationGateway,
            "delegationGateway is required"
        );
        this.handoffGateway = Objects.requireNonNull(
            handoffGateway,
            "handoffGateway is required"
        );
        this.conversationRecorder = conversationRecorder;
        this.turnCoordinator = turnCoordinator;
        this.repository = Objects.requireNonNull(
            repository,
            "repository is required"
        );
        this.codec = Objects.requireNonNull(codec, "codec is required");
        this.security = Objects.requireNonNull(
            security,
            "security is required"
        );
        this.taskExecutor = Objects.requireNonNull(
            taskExecutor,
            "taskExecutor is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.metrics = Objects.requireNonNull(
            metrics,
            "metrics is required"
        );
        AIExecutionProperties.SpecialistChains requiredProperties =
            Objects.requireNonNull(properties, "properties are required");
        this.durable = durable;
        this.maxActive = requiredProperties.getMaxActive();
        this.leaseDuration = requiredProperties.getLeaseDuration();
        this.retention = requiredProperties.getRetention();
        this.recoveryBatchSize =
            requiredProperties.getRecoveryBatchSize();
        this.maxAttempts = requiredProperties.getMaxAttempts();
        this.maxDirectiveCorrections =
            requiredProperties.getMaxDirectiveCorrections();
        this.cleanupEnabled = requiredProperties.isCleanupEnabled();
        this.workerId = "ai-fabric-chain-" + UUID.randomUUID();
    }

    @Override
    public <I> SpecialistChainExecutionResult execute(
        SpecialistChainExecutionRequest<I> request
    ) {
        Objects.requireNonNull(request, "request is required");
        Submission submission = prepare(request);
        if (submission.result() != null) {
            return submission.result();
        }
        if (submission.record().status()
            == SpecialistChainExecutionStatus.QUEUED) {
            run(submission.record().executionId());
        }
        return awaitTerminal(
            submission.record().executionId(),
            request.trustedExecutionContext(),
            submission.record().deadline(),
            submission.replayed()
        );
    }

    @Override
    public <I> SpecialistChainExecutionHandle submit(
        SpecialistChainExecutionRequest<I> request
    ) {
        Objects.requireNonNull(request, "request is required");
        Submission submission = prepare(request);
        if (submission.result() != null) {
            return handle(submission.result());
        }
        dispatch(submission.record().executionId());
        return handle(submission.record(), submission.replayed());
    }

    @Override
    public Optional<SpecialistChainExecutionSnapshot> find(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        return repository.findById(executionId.trim())
            .filter(record -> authorized(record, trustedExecutionContext))
            .map(this::snapshot);
    }

    @Override
    public Optional<SpecialistChainExecutionResult> findResult(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        return repository.findById(executionId.trim())
            .filter(record -> authorized(record, trustedExecutionContext))
            .filter(record -> record.status().terminal())
            .flatMap(record -> {
                try {
                    return Optional.of(codec.unprotectResult(record));
                } catch (RuntimeException ex) {
                    return Optional.empty();
                }
            });
    }

    @Override
    public boolean cancel(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        if (executionId == null || executionId.isBlank()) {
            return false;
        }
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        String normalizedId = executionId.trim();
        for (int attempt = 0; attempt < 4; attempt++) {
            SpecialistChainExecutionRecord current = repository
                .findById(normalizedId)
                .orElse(null);
            if (current == null
                || !authorized(current, trustedExecutionContext)
                || current.status().terminal()) {
                return false;
            }
            SpecialistChainExecutionResult result = failedResult(
                current,
                current.chainContentHash(),
                SpecialistChainExecutionStatus.CANCELLED,
                "CHAIN_CANCELLED",
                "The specialist chain was cancelled.",
                true,
                safeCheckpoint(current)
            );
            if (complete(current, result)) {
                ActiveExecution active = activeExecutions.remove(
                    normalizedId
                );
                if (active != null) {
                    active.cancel();
                }
                return true;
            }
        }
        return false;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverAtStartup() {
        recover();
    }

    @Scheduled(
        fixedDelayString =
            "${ai.execution.specialist-chains.recovery-interval:PT30S}"
    )
    public RecoverySummary recover() {
        Instant now = clock.instant();
        int dispatched = 0;
        int deadlineFailures = 0;
        int attemptFailures = 0;
        int deleted = 0;
        for (SpecialistChainExecutionRecord record :
            repository.findRecoverable(now, recoveryBatchSize)) {
            if (!now.isBefore(record.deadline())) {
                if (terminalize(
                    record,
                    SpecialistChainExecutionStatus.DEADLINE_EXCEEDED,
                    "CHAIN_DEADLINE_EXCEEDED",
                    "The specialist-chain deadline elapsed.",
                    true
                )) {
                    deadlineFailures++;
                }
            } else if (record.attemptCount() >= maxAttempts) {
                if (terminalize(
                    record,
                    SpecialistChainExecutionStatus.FAILED,
                    "CHAIN_RECOVERY_ATTEMPTS_EXHAUSTED",
                    "The specialist chain could not be recovered safely.",
                    false
                )) {
                    attemptFailures++;
                }
            } else if (dispatch(record.executionId())) {
                dispatched++;
            }
        }
        if (cleanupEnabled) {
            Instant cutoff = now.minus(retention);
            for (SpecialistChainExecutionRecord record :
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
            deadlineFailures,
            attemptFailures,
            deleted
        );
    }

    private <I> Submission prepare(
        SpecialistChainExecutionRequest<I> request
    ) {
        Instant startedAt = clock.instant();
        String proposedExecutionId = executionId();
        RegisteredSpecialistChain registered = registeredChain(
            request.chainId()
        );
        if (registered == null) {
            return rejectedSubmission(
                proposedExecutionId,
                request.chainId(),
                unknownHash(request.chainId()),
                SpecialistChainExecutionStatus.INVALID,
                "CHAIN_NOT_FOUND",
                "The requested specialist chain is not registered.",
                false,
                startedAt,
                request.deadline()
            );
        }
        if (!registered.definition().inputType().isInstance(
            request.input()
        )) {
            return rejectedSubmission(
                proposedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.INVALID,
                "CHAIN_INPUT_TYPE_INVALID",
                "The chain request does not satisfy its registered type.",
                false,
                startedAt,
                request.deadline()
            );
        }
        SpecialistChainFailure conversationFailure =
            validateConversationRequest(
                registered.definition().conversationPolicy(),
                request
            );
        if (conversationFailure != null) {
            return rejectedSubmission(
                proposedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.INVALID,
                conversationFailure.reason(),
                conversationFailure.publicMessage(),
                conversationFailure.retryable(),
                startedAt,
                request.deadline()
            );
        }

        Instant deadline = effectiveDeadline(
            request.deadline(),
            startedAt,
            registered.definition().limits().maxDuration()
        );
        SpecialistChainCheckpoint checkpoint;
        try {
            checkpoint = initialCheckpoint(registered, request);
        } catch (RuntimeException ex) {
            return rejectedSubmission(
                proposedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.INVALID,
                "CHAIN_INPUT_INVALID",
                "The application could not prepare safe manager input.",
                false,
                startedAt,
                deadline
            );
        }
        String accessFingerprint;
        String idempotencyFingerprint;
        String requestFingerprint;
        try {
            accessFingerprint = security.accessFingerprint(
                request.trustedExecutionContext()
            );
            idempotencyFingerprint = security.idempotencyFingerprint(
                request.trustedExecutionContext(),
                registered.id(),
                request.idempotencyKey()
            );
            requestFingerprint = requestFingerprint(request, registered);
        } catch (RuntimeException ex) {
            return rejectedSubmission(
                proposedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.INVALID,
                "CHAIN_FINGERPRINT_INVALID",
                "The chain request could not be snapshotted safely.",
                false,
                startedAt,
                deadline
            );
        }

        Submission replay = replayOrConflict(
            proposedExecutionId,
            registered,
            idempotencyFingerprint,
            requestFingerprint,
            startedAt,
            deadline
        );
        if (replay != null) {
            return replay;
        }
        if (!startedAt.isBefore(deadline)) {
            return rejectedSubmission(
                proposedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.DEADLINE_EXCEEDED,
                "CHAIN_DEADLINE_EXCEEDED",
                "The specialist-chain deadline has elapsed.",
                true,
                startedAt,
                deadline
            );
        }
        if (repository.countActive() >= maxActive) {
            return rejectedSubmission(
                proposedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.FAILED,
                "CHAIN_CAPACITY_EXCEEDED",
                "Specialist-chain capacity is temporarily exhausted.",
                true,
                startedAt,
                deadline
            );
        }

        try {
            SpecialistChainExecutionRequest<I> effectiveRequest =
                new SpecialistChainExecutionRequest<>(
                    request.chainId(),
                    request.input(),
                    request.trustedExecutionContext(),
                    request.conversationBinding(),
                    deadline,
                    request.idempotencyKey()
                );
            SpecialistChainExecutionRecord queued =
                SpecialistChainExecutionRecord.queued(
                    proposedExecutionId,
                    registered.id(),
                    registered.contentHash(),
                    registered.definition().managerSpecialistId(),
                    registered.managerContentHash(),
                    accessFingerprint,
                    idempotencyFingerprint,
                    requestFingerprint,
                    codec.protectRequest(
                        proposedExecutionId,
                        effectiveRequest
                    ),
                    codec.protectCheckpoint(
                        proposedExecutionId,
                        checkpoint
                    ),
                    deadline,
                    startedAt,
                    retention
                );
            repository.create(queued);
            metrics.started(registered.id(), durable);
            return new Submission(queued, null, false);
        } catch (
            SpecialistChainExecutionRepository
                .DuplicateChainExecutionException ex
        ) {
            Submission raced = replayOrConflict(
                executionId(),
                registered,
                idempotencyFingerprint,
                requestFingerprint,
                startedAt,
                deadline
            );
            return raced == null
                ? rejectedSubmission(
                    executionId(),
                    registered.id(),
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.INVALID,
                    "CHAIN_IDEMPOTENCY_CONFLICT",
                    "The chain idempotency key was already used for different work.",
                    false,
                    startedAt,
                    deadline
                )
                : raced;
        } catch (RuntimeException ex) {
            log.warn(
                "Specialist chain {} could not persist initial state: {}",
                registered.id(),
                ex.getClass().getSimpleName()
            );
            return rejectedSubmission(
                proposedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.FAILED,
                "CHAIN_STATE_UNAVAILABLE",
                "The specialist-chain state could not be persisted safely.",
                true,
                startedAt,
                deadline
            );
        }
    }

    private RegisteredSpecialistChain registeredChain(
        ai.fabric.execution.chain.SpecialistChainId id
    ) {
        return chainRegistry.find(id).orElse(null);
    }

    private <I> SpecialistChainCheckpoint initialCheckpoint(
        RegisteredSpecialistChain registered,
        SpecialistChainExecutionRequest<I> request
    ) {
        SpecialistChainDefinition<I> definition = typedDefinition(
            registered,
            request.input()
        );
        return SpecialistChainCheckpoint.initial(
            definition.inputAdapter().currentUserMessage(request.input()),
            definition.inputAdapter().applicationContext(request.input()),
            null,
            0
        );
    }

    private SpecialistChainFailure validateConversationRequest(
        SpecialistChainConversationPolicy policy,
        SpecialistChainExecutionRequest<?> request
    ) {
        if (policy == SpecialistChainConversationPolicy.DISABLED
            && request.conversationBinding() != null) {
            return new SpecialistChainFailure(
                "CHAIN_CONVERSATION_DISABLED",
                "This specialist chain does not accept a conversation binding.",
                false
            );
        }
        if (policy == SpecialistChainConversationPolicy.REQUIRED
            && request.conversationBinding() == null) {
            return new SpecialistChainFailure(
                "CHAIN_CONVERSATION_REQUIRED",
                "This specialist chain requires a backend conversation binding.",
                false
            );
        }
        if (request.conversationBinding() != null
            && request.trustedExecutionContext().source()
                != ExecutionSource.INTERACTIVE) {
            return new SpecialistChainFailure(
                "CHAIN_INTERACTIVE_SOURCE_REQUIRED",
                "A conversation-bound chain requires an authenticated interactive request.",
                false
            );
        }
        if (request.conversationBinding() != null
            && (turnCoordinator == null || conversationRecorder == null)) {
            return new SpecialistChainFailure(
                "CHAIN_CONVERSATION_UNAVAILABLE",
                "Backend conversation support is not available for this chain.",
                false
            );
        }
        return null;
    }

    private Submission replayOrConflict(
        String rejectedExecutionId,
        RegisteredSpecialistChain registered,
        String idempotencyFingerprint,
        String requestFingerprint,
        Instant startedAt,
        Instant deadline
    ) {
        SpecialistChainExecutionRecord existing = repository
            .findByIdempotencyFingerprint(idempotencyFingerprint)
            .orElse(null);
        if (existing == null) {
            return null;
        }
        if (!security.sameFingerprint(
                existing.requestFingerprint(),
                requestFingerprint
            )
            || !security.sameFingerprint(
                existing.chainContentHash(),
                registered.contentHash()
            )
            || !security.sameFingerprint(
                existing.managerContentHash(),
                registered.managerContentHash()
            )) {
            return rejectedSubmission(
                rejectedExecutionId,
                registered.id(),
                registered.contentHash(),
                SpecialistChainExecutionStatus.INVALID,
                "CHAIN_IDEMPOTENCY_CONFLICT",
                "The chain idempotency key was already used for different work.",
                false,
                startedAt,
                deadline
            );
        }
        if (existing.status().terminal()) {
            try {
                SpecialistChainExecutionResult result =
                    codec.unprotectResult(existing).asReplayed();
                metrics.replayed(registered.id());
                return new Submission(existing, result, true);
            } catch (RuntimeException ex) {
                return rejectedSubmission(
                    rejectedExecutionId,
                    registered.id(),
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.FAILED,
                    "CHAIN_RESULT_UNAVAILABLE",
                    "The stored specialist-chain result could not be verified.",
                    false,
                    startedAt,
                    deadline
                );
            }
        }
        metrics.replayed(registered.id());
        return new Submission(existing, null, true);
    }

    private boolean dispatch(String executionId) {
        ActiveExecution active = new ActiveExecution();
        if (activeExecutions.putIfAbsent(executionId, active) != null) {
            return false;
        }
        try {
            Future<?> root = taskExecutor.submit(() -> run(executionId));
            active.setRoot(root);
            return true;
        } catch (RejectedExecutionException ex) {
            activeExecutions.remove(executionId, active);
            log.debug(
                "Specialist chain {} remains queued for recovery",
                executionId
            );
            return false;
        }
    }

    private void run(String executionId) {
        SpecialistChainExecutionRecord claimed = claim(executionId)
            .orElse(null);
        if (claimed == null) {
            activeExecutions.remove(executionId);
            return;
        }
        try {
            executeClaimed(claimed);
        } catch (RuntimeException ex) {
            log.warn(
                "Specialist chain {} failed before a safe result: {}",
                executionId,
                ex.getClass().getSimpleName()
            );
            SpecialistChainExecutionRecord current = repository
                .findById(executionId)
                .orElse(claimed);
            if (!current.status().terminal()) {
                terminalize(
                    current,
                    SpecialistChainExecutionStatus.FAILED,
                    "CHAIN_EXECUTION_FAILED",
                    "The specialist chain could not be completed safely.",
                    false
                );
            }
        } finally {
            activeExecutions.remove(executionId);
        }
    }

    private Optional<SpecialistChainExecutionRecord> claim(
        String executionId
    ) {
        for (int attempt = 0; attempt < 4; attempt++) {
            SpecialistChainExecutionRecord current = repository
                .findById(executionId)
                .orElse(null);
            if (current == null
                || !current.claimable(clock.instant(), maxAttempts)) {
                return Optional.empty();
            }
            Instant now = clock.instant();
            SpecialistChainExecutionRecord claimed = current.claimed(
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

    private void executeClaimed(
        SpecialistChainExecutionRecord claimed
    ) {
        RegisteredSpecialistChain registered = chainRegistry
            .find(claimed.chainId())
            .orElse(null);
        if (registered == null
            || !security.sameFingerprint(
                claimed.chainContentHash(),
                registered.contentHash()
            )
            || !security.sameFingerprint(
                claimed.managerContentHash(),
                registered.managerContentHash()
            )) {
            terminalize(
                claimed,
                SpecialistChainExecutionStatus.INVALID,
                "CHAIN_DEFINITION_CHANGED",
                "The chain definition changed before execution resumed.",
                false
            );
            return;
        }

        SpecialistChainExecutionRequest<Object> request;
        SpecialistChainCheckpoint checkpoint;
        try {
            request = codec.unprotectRequest(claimed);
            checkpoint = codec.unprotectCheckpoint(claimed);
        } catch (RuntimeException ex) {
            terminalize(
                claimed,
                SpecialistChainExecutionStatus.FAILED,
                "CHAIN_STATE_INVALID",
                "The protected specialist-chain state could not be verified.",
                false
            );
            return;
        }
        ExecutionCursor cursor = new ExecutionCursor(
            claimed,
            checkpoint
        );
        boolean recovered = claimed.attemptCount() > 1;
        if (isUncertain(checkpoint.phase()) && recovered) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.FAILED,
                    uncertainReason(checkpoint.phase()),
                    "A provider operation was in flight when execution stopped; it was not rerun.",
                    true,
                    checkpoint
                )
            );
            return;
        }

        if (request.conversationBinding() == null) {
            executeLoop(
                cursor,
                registered,
                request,
                null,
                null
            );
            return;
        }
        SharedInteractiveTurnCoordinator.CoordinatedTurn<Boolean> turn =
            turnCoordinator.coordinate(
                registered.definition().managerSpecialistId(),
                request.trustedExecutionContext(),
                request.conversationBinding(),
                request.idempotencyKey(),
                SharedInteractiveTurnCoordinator
                    .RecordingPolicy.COORDINATED,
                approved -> {
                    if (!approveConversationSnapshot(cursor, approved)) {
                        return Boolean.TRUE;
                    }
                    executeLoop(
                        cursor,
                        registered,
                        request,
                        approved.approvedBinding(),
                        approved.snapshot()
                    );
                    return Boolean.TRUE;
                }
            );
        if (!turn.succeeded() && !cursor.record.status().terminal()) {
            AIExecutionFailure failure = turn.failure();
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    "CONVERSATION_BUSY".equals(failure.reason())
                        ? SpecialistChainExecutionStatus.DENIED
                        : SpecialistChainExecutionStatus.INVALID,
                    failure.reason(),
                    failure.publicMessage(),
                    failure.retryable(),
                    cursor.checkpoint
                )
            );
        }
    }

    private boolean approveConversationSnapshot(
        ExecutionCursor cursor,
        SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn turn
    ) {
        String previous = cursor.checkpoint.conversationSnapshotRevision();
        if (previous != null
            && !previous.equals(turn.snapshot().revision())) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    cursor.record.chainContentHash(),
                    SpecialistChainExecutionStatus.INVALID,
                    "CHAIN_CONVERSATION_CHANGED",
                    "The conversation changed before the chain could resume safely.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }
        if (previous == null) {
            SpecialistChainCheckpoint updated = copyCheckpoint(
                cursor.checkpoint,
                cursor.checkpoint.phase(),
                cursor.checkpoint.projectedResults(),
                cursor.checkpoint.steps(),
                cursor.checkpoint.managerDecisionCount(),
                cursor.checkpoint.workerInvocationCount(),
                cursor.checkpoint.projectedResultCharacters(),
                cursor.checkpoint.targetInvocationCounts(),
                cursor.checkpoint.lastDirectiveHash(),
                cursor.checkpoint.managerExecution(),
                turn.snapshot().revision(),
                turn.snapshot().sourceTurnCount()
            );
            return checkpoint(cursor, updated);
        }
        return true;
    }

    private void executeLoop(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        SpecialistChainExecutionRequest<Object> request,
        ConversationBinding approvedBinding,
        ApprovedConversationSnapshot snapshot
    ) {
        SpecialistChainDefinition<Object> definition = typedDefinition(
            registered,
            request.input()
        );
        boolean initialManagerBindingAvailable = approvedBinding != null;
        while (!cursor.record.status().terminal()) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                terminal(
                    cursor,
                    failedResult(
                        cursor.record,
                        registered.contentHash(),
                        SpecialistChainExecutionStatus.CANCELLED,
                        "CHAIN_CANCELLED",
                        "The specialist chain was cancelled.",
                        true,
                        cursor.checkpoint
                    )
                );
                return;
            }
            if (!clock.instant().isBefore(cursor.record.deadline())) {
                terminal(
                    cursor,
                    failedResult(
                        cursor.record,
                        registered.contentHash(),
                        SpecialistChainExecutionStatus.DEADLINE_EXCEEDED,
                        "CHAIN_DEADLINE_EXCEEDED",
                        "The specialist-chain deadline elapsed.",
                        true,
                        cursor.checkpoint
                    )
                );
                return;
            }
            if (cursor.checkpoint.phase()
                == SpecialistChainCheckpointPhase.READY_FOR_MANAGER) {
                ConversationBinding managerBinding = approvedBinding;
                boolean releaseManagerBinding = false;
                if (snapshot != null && !initialManagerBindingAvailable) {
                    managerBinding = turnCoordinator.approveInvocation(
                        request.conversationBinding(),
                        snapshot
                    );
                    releaseManagerBinding = true;
                }
                try {
                    if (!invokeManager(
                        cursor,
                        registered,
                        definition,
                        request,
                        managerBinding,
                        snapshot
                    )) {
                        return;
                    }
                } finally {
                    initialManagerBindingAvailable = false;
                    if (releaseManagerBinding) {
                        turnCoordinator.releaseInvocation(managerBinding);
                    }
                }
            }
            if (cursor.checkpoint.phase()
                == SpecialistChainCheckpointPhase.DIRECTIVE_ACCEPTED) {
                if (!applyDirective(
                    cursor,
                    registered,
                    definition,
                    request,
                    approvedBinding,
                    snapshot
                )) {
                    return;
                }
                continue;
            }
            if (isUncertain(cursor.checkpoint.phase())) {
                terminal(
                    cursor,
                    failedResult(
                        cursor.record,
                        registered.contentHash(),
                        SpecialistChainExecutionStatus.FAILED,
                        uncertainReason(cursor.checkpoint.phase()),
                        "A provider operation has an uncertain outcome and was not rerun.",
                        true,
                        cursor.checkpoint
                    )
                );
                return;
            }
        }
    }

    private boolean invokeManager(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        SpecialistChainDefinition<Object> definition,
        SpecialistChainExecutionRequest<Object> request,
        ConversationBinding approvedBinding,
        ApprovedConversationSnapshot snapshot
    ) {
        if (cursor.checkpoint.managerDecisionCount()
            >= definition.limits().maxManagerDecisions()) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.FAILED,
                    "CHAIN_BUDGET_EXCEEDED",
                    "The specialist chain exhausted its manager-decision budget.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }
        SpecialistChainCheckpoint inFlight = copyCheckpoint(
            cursor.checkpoint,
            SpecialistChainCheckpointPhase.MANAGER_IN_FLIGHT,
            cursor.checkpoint.projectedResults(),
            cursor.checkpoint.steps(),
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount(),
            cursor.checkpoint.projectedResultCharacters(),
            cursor.checkpoint.targetInvocationCounts(),
            cursor.checkpoint.lastDirectiveHash(),
            null,
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        if (!checkpoint(cursor, inFlight)) {
            return false;
        }

        int decisionIndex = cursor.checkpoint.managerDecisionCount();
        AIExecutionResult<SpecialistChainDirective> execution = null;
        SpecialistChainDirective directive = null;
        String directiveFeedback = null;
        for (int correctionAttempt = 0;
             correctionAttempt <= maxDirectiveCorrections;
             correctionAttempt++) {
            SpecialistChainManagerInput managerInput = managerInput(
                cursor,
                definition,
                directiveFeedback
            );
            ConversationBinding invocationBinding = approvedBinding;
            boolean releaseInvocationBinding = false;
            try {
                if (correctionAttempt > 0 && snapshot != null) {
                    invocationBinding = turnCoordinator.approveInvocation(
                        request.conversationBinding(),
                        snapshot
                    );
                    releaseInvocationBinding = true;
                }
                SpecialistClient<
                    SpecialistChainManagerInput,
                    SpecialistChainDirective
                > client = clientFactory.bind(
                    definition.managerSpecialistId(),
                    SpecialistChainManagerInput.class,
                    SpecialistChainDirective.class
                );
                metrics.modelCall(
                    registered.id(),
                    "manager",
                    definition.managerSpecialistId().toString()
                );
                execution = client.execute(new SpecialistInvocation<>(
                    managerInput,
                    request.trustedExecutionContext(),
                    invocationBinding,
                    cursor.record.deadline(),
                    managerIdempotencyKey(
                        cursor.record,
                        decisionIndex,
                        correctionAttempt
                    )
                ));
            } catch (RuntimeException ex) {
                terminal(
                    cursor,
                    failedResult(
                        cursor.record,
                        registered.contentHash(),
                        SpecialistChainExecutionStatus.FAILED,
                        "CHAIN_MANAGER_INVOCATION_FAILED",
                        "The chain manager could not be invoked safely.",
                        true,
                        cursor.checkpoint
                    )
                );
                return false;
            } finally {
                if (releaseInvocationBinding) {
                    turnCoordinator.releaseInvocation(invocationBinding);
                }
            }
            if (!clock.instant().isBefore(cursor.record.deadline())) {
                return failDeadline(cursor, registered.contentHash());
            }
            if (!execution.succeeded() || execution.output() == null) {
                AIExecutionFailure failure = execution.failure();
                terminal(
                    cursor,
                    failedResult(
                        cursor.record,
                        registered.contentHash(),
                        mapStatus(execution.status()),
                        failure == null
                            ? "CHAIN_MANAGER_RESULT_INVALID"
                            : failure.reason(),
                        failure == null
                            ? "The chain manager did not return a valid directive."
                            : failure.publicMessage(),
                        failure != null && failure.retryable(),
                        cursor.checkpoint
                    )
                );
                return false;
            }
            directive = execution.output();
            SpecialistChainFailure validation = validateDirective(
                definition,
                cursor.checkpoint,
                directive
            );
            if (validation == null) {
                break;
            }
            directiveFeedback = correctionFeedback(
                validation,
                managerInput
            );
            if (directiveFeedback != null
                && correctionAttempt < maxDirectiveCorrections) {
                metrics.managerCorrection(
                    registered.id(),
                    validation.reason()
                );
                log.info(
                    "Specialist chain {} manager directive rejected "
                        + "reason={} correctionAttempt={} directiveType={} "
                        + "requestedTargets={} eligibleTargets={}",
                    cursor.record.executionId(),
                    validation.reason(),
                    correctionAttempt + 1,
                    directive.type(),
                    directive.targets().stream()
                        .map(SpecialistChainTargetRequest::targetSpecialist)
                        .toList(),
                    managerInput.approvedTargets().stream()
                        .map(target -> target.specialist())
                        .toList()
                );
                continue;
            }
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    validationStatus(validation),
                    validation.reason(),
                    validation.publicMessage(),
                    validation.retryable(),
                    cursor.checkpoint
                )
            );
            return false;
        }
        if (execution == null || directive == null) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.FAILED,
                    "CHAIN_MANAGER_RESULT_INVALID",
                    "The chain manager did not return a valid directive.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }
        String directiveHash = directiveStateHash(
            directive,
            cursor.checkpoint.projectedResults()
        );
        if (directiveHash.equals(
            cursor.checkpoint.lastDirectiveHash()
        )) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.INVALID,
                    "CHAIN_NO_PROGRESS",
                    "The chain manager repeated a directive without new approved state.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }

        SpecialistChainCheckpoint accepted = copyCheckpoint(
            cursor.checkpoint,
            SpecialistChainCheckpointPhase.DIRECTIVE_ACCEPTED,
            cursor.checkpoint.projectedResults(),
            cursor.checkpoint.steps(),
            cursor.checkpoint.managerDecisionCount() + 1,
            cursor.checkpoint.workerInvocationCount(),
            cursor.checkpoint.projectedResultCharacters(),
            cursor.checkpoint.targetInvocationCounts(),
            directiveHash,
            new SpecialistChainManagerExecutionCheckpoint(
                execution.invocationId(),
                directive,
                execution.startedAt(),
                execution.completedAt()
            ),
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        metrics.managerDecision(registered.id(), directive.type());
        return checkpoint(cursor, accepted);
    }

    private SpecialistChainManagerInput managerInput(
        ExecutionCursor cursor,
        SpecialistChainDefinition<?> definition,
        String directiveFeedback
    ) {
        return new SpecialistChainManagerInput(
            cursor.checkpoint.currentUserMessage(),
            cursor.checkpoint.applicationContext(),
            definition.targets().stream()
                .filter(target -> cursor.checkpoint
                    .targetInvocationCounts()
                    .getOrDefault(target.specialistId().toString(), 0)
                    < definition.limits().maxInvocationsPerTarget())
                .map(SpecialistChainTarget::view)
                .toList(),
            cursor.checkpoint.projectedResults(),
            remainingBudget(definition, cursor),
            directiveFeedback
        );
    }

    private String correctionFeedback(
        SpecialistChainFailure validation,
        SpecialistChainManagerInput input
    ) {
        List<String> eligibleTargets = input.approvedTargets().stream()
            .map(target -> target.specialist())
            .toList();
        List<String> requiredResults = input.completedResults().stream()
            .map(SpecialistChainResultView::resultId)
            .toList();
        return switch (validation.reason()) {
            case "CHAIN_NO_PROGRESS" -> eligibleTargets.isEmpty()
                ? "REQUIRED CORRECTION: the runtime rejected the previous "
                    + "directive because no specialist remains eligible. "
                    + "Return COMPLETE now with targets=[], a concise "
                    + "grounded message, and supportingResultIds exactly "
                    + catalog(requiredResults) + ". Do not return ASK_USER, "
                    + "INVOKE_ONE, INVOKE_PARALLEL, or HANDOFF."
                : "REQUIRED CORRECTION: the runtime rejected the previous "
                    + "directive because it selected a specialist that is "
                    + "no longer eligible. Select only these currently "
                    + "eligible exact IDs: " + catalog(eligibleTargets)
                    + ", or return COMPLETE. Do not select a completed "
                    + "specialist. If returning COMPLETE, set "
                    + "supportingResultIds exactly to "
                    + catalog(requiredResults) + ".";
            case "CHAIN_FINAL_GROUNDING_INVALID" ->
                "The runtime rejected the previous COMPLETE directive "
                    + "because its result attribution was not exact. "
                    + "Choose COMPLETE with no targets and set "
                    + "supportingResultIds to exactly: "
                    + catalog(requiredResults) + ".";
            default -> null;
        };
    }

    private static String catalog(List<String> values) {
        return values.isEmpty()
            ? "[]"
            : "[" + String.join(", ", values) + "]";
    }

    private SpecialistChainFailure validateDirective(
        SpecialistChainDefinition<?> definition,
        SpecialistChainCheckpoint checkpoint,
        SpecialistChainDirective directive
    ) {
        if (directive.type() == SpecialistChainDirectiveType.COMPLETE) {
            Set<String> availableResultIds = checkpoint.projectedResults()
                .stream()
                .map(SpecialistChainResultView::resultId)
                .collect(java.util.stream.Collectors.toSet());
            Set<String> supportingResultIds = Set.copyOf(
                directive.supportingResultIds()
            );
            if (!availableResultIds.equals(supportingResultIds)) {
                return failure(
                    "CHAIN_FINAL_GROUNDING_INVALID",
                    availableResultIds.isEmpty()
                        ? "A completion without worker evidence cannot cite specialist results."
                        : "The final answer must cite every approved specialist result and no other result.",
                    false
                );
            }
        }
        List<SpecialistChainTarget<?, ?, ?>> targets = new ArrayList<>();
        for (SpecialistChainTargetRequest request : directive.targets()) {
            SpecialistChainTarget<?, ?, ?> target = findTarget(
                definition,
                request.specialistId()
            );
            if (target == null) {
                return failure(
                    "CHAIN_TARGET_NOT_ALLOWED",
                    "The chain manager selected a specialist outside its approved target set.",
                    false
                );
            }
            targets.add(target);
            int count = checkpoint.targetInvocationCounts()
                .getOrDefault(request.targetSpecialist(), 0);
            if (count >= definition.limits().maxInvocationsPerTarget()) {
                return failure(
                    "CHAIN_NO_PROGRESS",
                    "The chain manager selected a specialist that already completed.",
                    false
                );
            }
        }
        if (directive.type() == SpecialistChainDirectiveType.INVOKE_ONE
            && !targets.getFirst().delegationAllowed()) {
            return failure(
                "CHAIN_TARGET_NOT_ALLOWED",
                "The selected specialist is not approved for delegation.",
                false
            );
        }
        if (directive.type()
            == SpecialistChainDirectiveType.INVOKE_PARALLEL) {
            if (targets.size() > definition.limits().maxParallelWorkers()) {
                return failure(
                    "CHAIN_PARALLEL_BUDGET_EXCEEDED",
                    "The manager selected too many parallel specialists.",
                    false
                );
            }
            if (targets.stream().anyMatch(target ->
                !target.delegationAllowed() || !target.parallelEligible()
            )) {
                return failure(
                    "CHAIN_PARALLEL_TARGET_NOT_ALLOWED",
                    "Every parallel specialist must be explicitly independent and approved.",
                    false
                );
            }
        }
        if (directive.type() == SpecialistChainDirectiveType.HANDOFF
            && !targets.getFirst().handoffAllowed()) {
            return failure(
                "CHAIN_HANDOFF_NOT_ALLOWED",
                "The selected specialist is not approved for terminal handoff.",
                false
            );
        }
        if (directive.type() == SpecialistChainDirectiveType.INVOKE_ONE
            || directive.type()
                == SpecialistChainDirectiveType.INVOKE_PARALLEL) {
            if (checkpoint.workerInvocationCount() + targets.size()
                > definition.limits().maxWorkerInvocations()
                || checkpoint.managerDecisionCount() + 1
                    >= definition.limits().maxManagerDecisions()) {
                return failure(
                    "CHAIN_BUDGET_EXCEEDED",
                    "The chain cannot invoke more specialists while reserving a final manager decision.",
                    false
                );
            }
        }
        return null;
    }

    private static SpecialistChainExecutionStatus validationStatus(
        SpecialistChainFailure validation
    ) {
        return switch (validation.reason()) {
            case "CHAIN_TARGET_NOT_ALLOWED",
                "CHAIN_PARALLEL_TARGET_NOT_ALLOWED",
                "CHAIN_HANDOFF_NOT_ALLOWED" ->
                SpecialistChainExecutionStatus.DENIED;
            default -> SpecialistChainExecutionStatus.INVALID;
        };
    }

    private boolean applyDirective(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        SpecialistChainDefinition<Object> definition,
        SpecialistChainExecutionRequest<Object> request,
        ConversationBinding approvedBinding,
        ApprovedConversationSnapshot snapshot
    ) {
        SpecialistChainManagerExecutionCheckpoint manager =
            cursor.checkpoint.managerExecution();
        SpecialistChainDirective directive = manager.directive();
        return switch (directive.type()) {
            case ASK_USER -> finishWithMessage(
                cursor,
                registered,
                request,
                directive,
                SpecialistChainExecutionStatus.ASKED_USER,
                null,
                List.of(),
                snapshot
            );
            case COMPLETE -> finishWithMessage(
                cursor,
                registered,
                request,
                directive,
                SpecialistChainExecutionStatus.COMPLETED,
                null,
                List.of(),
                snapshot
            );
            case INVOKE_ONE -> invokeOne(
                cursor,
                registered,
                definition,
                request,
                managerExecution(registered, cursor),
                directive.requiredSingleTarget()
            );
            case INVOKE_PARALLEL -> invokeParallel(
                cursor,
                registered,
                definition,
                request,
                managerExecution(registered, cursor),
                directive.targets()
            );
            case HANDOFF -> invokeHandoff(
                cursor,
                registered,
                definition,
                request,
                managerExecution(registered, cursor),
                directive.requiredSingleTarget(),
                snapshot
            );
        };
    }

    private boolean invokeOne(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        SpecialistChainDefinition<Object> definition,
        SpecialistChainExecutionRequest<Object> request,
        AIExecutionResult<SpecialistChainDirective> managerExecution,
        SpecialistChainTargetRequest targetRequest
    ) {
        PreparedWorker prepared;
        try {
            prepared = prepareWorker(
                definition,
                request.input(),
                targetRequest
            );
        } catch (RuntimeException ex) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.INVALID,
                    "CHAIN_TARGET_INPUT_INVALID",
                    "The application could not prepare safe specialist input.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }
        SpecialistChainCheckpoint inFlight = copyCheckpoint(
            cursor.checkpoint,
            SpecialistChainCheckpointPhase.WORKERS_IN_FLIGHT,
            cursor.checkpoint.projectedResults(),
            cursor.checkpoint.steps(),
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount(),
            cursor.checkpoint.projectedResultCharacters(),
            cursor.checkpoint.targetInvocationCounts(),
            cursor.checkpoint.lastDirectiveHash(),
            cursor.checkpoint.managerExecution(),
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        if (!checkpoint(cursor, inFlight)) {
            return false;
        }
        metrics.workerSelected(
            registered.id(),
            prepared.target.specialistId().toString(),
            false
        );
        WorkerAttempt attempt = invokeWorker(
            cursor.record,
            request,
            managerExecution,
            prepared,
            cursor.checkpoint.managerDecisionCount() - 1,
            null
        );
        if (!clock.instant().isBefore(cursor.record.deadline())) {
            return failDeadline(cursor, registered.contentHash());
        }
        if (!attempt.succeeded()) {
            return failWorkerGroup(
                cursor,
                registered,
                List.of(attempt)
            );
        }
        return continueAfterWorkers(
            cursor,
            definition,
            List.of(attempt),
            null
        );
    }

    private boolean invokeParallel(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        SpecialistChainDefinition<Object> definition,
        SpecialistChainExecutionRequest<Object> request,
        AIExecutionResult<SpecialistChainDirective> managerExecution,
        List<SpecialistChainTargetRequest> targetRequests
    ) {
        List<PreparedWorker> prepared = new ArrayList<>();
        try {
            for (SpecialistChainTargetRequest targetRequest :
                targetRequests) {
                prepared.add(prepareWorker(
                    definition,
                    request.input(),
                    targetRequest
                ));
            }
            prepared.sort(Comparator.comparingInt(value ->
                targetIndex(definition, value.target.specialistId())
            ));
        } catch (RuntimeException ex) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.INVALID,
                    "CHAIN_TARGET_INPUT_INVALID",
                    "The application could not prepare every parallel specialist input.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }
        SpecialistChainCheckpoint inFlight = copyCheckpoint(
            cursor.checkpoint,
            SpecialistChainCheckpointPhase.WORKERS_IN_FLIGHT,
            cursor.checkpoint.projectedResults(),
            cursor.checkpoint.steps(),
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount(),
            cursor.checkpoint.projectedResultCharacters(),
            cursor.checkpoint.targetInvocationCounts(),
            cursor.checkpoint.lastDirectiveHash(),
            cursor.checkpoint.managerExecution(),
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        if (!checkpoint(cursor, inFlight)) {
            return false;
        }

        String groupId = "parallel-" + CanonicalJsonSupport.sha256(
            cursor.record.executionId() + "\n"
                + (cursor.checkpoint.managerDecisionCount() - 1)
        ).substring(0, 20);
        Instant groupStarted = clock.instant();
        ExecutorCompletionService<IndexedWorkerAttempt> completion =
            new ExecutorCompletionService<>(taskExecutor);
        List<Future<IndexedWorkerAttempt>> futures = new ArrayList<>();
        ActiveExecution active = activeExecutions.computeIfAbsent(
            cursor.record.executionId(),
            ignored -> new ActiveExecution()
        );
        for (int index = 0; index < prepared.size(); index++) {
            int branchIndex = index;
            PreparedWorker worker = prepared.get(index);
            metrics.workerSelected(
                registered.id(),
                worker.target.specialistId().toString(),
                true
            );
            Future<IndexedWorkerAttempt> future = completion.submit(() ->
                new IndexedWorkerAttempt(
                    branchIndex,
                    invokeWorker(
                        cursor.record,
                        request,
                        managerExecution,
                        worker,
                        cursor.checkpoint.managerDecisionCount() - 1,
                        groupId
                    )
                )
            );
            futures.add(future);
            active.addBranch(future);
        }
        WorkerAttempt[] ordered = new WorkerAttempt[prepared.size()];
        try {
            for (int completed = 0; completed < prepared.size(); completed++) {
                long remainingNanos = Duration.between(
                    clock.instant(),
                    cursor.record.deadline()
                ).toNanos();
                if (remainingNanos <= 0) {
                    cancel(futures);
                    return failParallelDeadline(
                        cursor,
                        registered,
                        ordered
                    );
                }
                Future<IndexedWorkerAttempt> completedFuture =
                    completion.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (completedFuture == null) {
                    cancel(futures);
                    return failParallelDeadline(
                        cursor,
                        registered,
                        ordered
                    );
                }
                IndexedWorkerAttempt indexed = completedFuture.get();
                ordered[indexed.index] = indexed.attempt;
                if (!indexed.attempt.succeeded()) {
                    cancel(futures);
                    return failWorkerGroup(
                        cursor,
                        registered,
                        java.util.Arrays.asList(ordered)
                    );
                }
            }
        } catch (InterruptedException ex) {
            cancel(futures);
            Thread.currentThread().interrupt();
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.CANCELLED,
                    "CHAIN_CANCELLED",
                    "The specialist chain was cancelled.",
                    true,
                    cursor.checkpoint
                )
            );
            return false;
        } catch (ExecutionException | CancellationException ex) {
            cancel(futures);
            int unresolved = 0;
            while (unresolved < ordered.length
                && ordered[unresolved] != null) {
                unresolved++;
            }
            if (unresolved < ordered.length) {
                PreparedWorker worker = prepared.get(unresolved);
                Instant now = clock.instant();
                ordered[unresolved] = WorkerAttempt.failed(
                    new SpecialistChainWorkerTrace(
                        worker.target.specialistId().toString(),
                        "DELEGATION",
                        null,
                        null,
                        SpecialistChainWorkerStatus.FAILED,
                        List.of(),
                        "CHAIN_PARALLEL_WORKER_FAILED",
                        groupStarted,
                        now
                    ),
                    "A required parallel specialist did not return safely.",
                    ex instanceof CancellationException
                );
            }
            return failWorkerGroup(
                cursor,
                registered,
                java.util.Arrays.asList(ordered)
            );
        } finally {
            active.removeBranches(futures);
            metrics.parallelGroup(
                registered.id(),
                prepared.size(),
                Duration.between(groupStarted, clock.instant())
            );
        }
        if (!clock.instant().isBefore(cursor.record.deadline())) {
            return failDeadline(cursor, registered.contentHash());
        }
        return continueAfterWorkers(
            cursor,
            definition,
            java.util.Arrays.asList(ordered),
            groupId
        );
    }

    private boolean invokeHandoff(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        SpecialistChainDefinition<Object> definition,
        SpecialistChainExecutionRequest<Object> request,
        AIExecutionResult<SpecialistChainDirective> managerExecution,
        SpecialistChainTargetRequest targetRequest,
        ApprovedConversationSnapshot snapshot
    ) {
        PreparedWorker prepared;
        try {
            prepared = prepareWorker(
                definition,
                request.input(),
                targetRequest
            );
        } catch (RuntimeException ex) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    registered.contentHash(),
                    SpecialistChainExecutionStatus.INVALID,
                    "CHAIN_HANDOFF_INPUT_INVALID",
                    "The application could not prepare safe handoff input.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }
        SpecialistChainCheckpoint inFlight = copyCheckpoint(
            cursor.checkpoint,
            SpecialistChainCheckpointPhase.HANDOFF_IN_FLIGHT,
            cursor.checkpoint.projectedResults(),
            cursor.checkpoint.steps(),
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount(),
            cursor.checkpoint.projectedResultCharacters(),
            cursor.checkpoint.targetInvocationCounts(),
            cursor.checkpoint.lastDirectiveHash(),
            cursor.checkpoint.managerExecution(),
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        if (!checkpoint(cursor, inFlight)) {
            return false;
        }
        WorkerAttempt attempt = handoffWorker(
            cursor.record,
            request,
            managerExecution,
            prepared,
            cursor.checkpoint.managerDecisionCount() - 1
        );
        if (!clock.instant().isBefore(cursor.record.deadline())) {
            return failDeadline(cursor, registered.contentHash());
        }
        if (!attempt.succeeded()) {
            return failWorkerGroup(
                cursor,
                registered,
                List.of(attempt)
            );
        }
        SpecialistChainDirective directive =
            cursor.checkpoint.managerExecution().directive();
        return finishWithMessage(
            cursor,
            registered,
            request,
            directive,
            SpecialistChainExecutionStatus.HANDED_OFF,
            prepared.target.specialistId(),
            List.of(attempt),
            snapshot
        );
    }

    private WorkerAttempt invokeWorker(
        SpecialistChainExecutionRecord record,
        SpecialistChainExecutionRequest<Object> request,
        AIExecutionResult<SpecialistChainDirective> managerExecution,
        PreparedWorker prepared,
        int decisionIndex,
        String parallelGroupId
    ) {
        Instant startedAt = clock.instant();
        try {
            metrics.modelCall(
                record.chainId(),
                "worker",
                prepared.target.specialistId().toString()
            );
            SpecialistDelegationResult<
                SpecialistChainDirective,
                Object
            > delegated = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    managerExecution,
                    prepared.target.specialistId(),
                    prepared.targetInput,
                    request.trustedExecutionContext(),
                    record.deadline(),
                    childIdempotencyKey(
                        record,
                        decisionIndex,
                        prepared.target.specialistId(),
                        parallelGroupId,
                        "delegation"
                    )
                ),
                prepared.target.inputMapper().targetInputType(),
                prepared.target.resultProjector().targetOutputType()
            );
            if (!delegated.succeeded()) {
                String reason = delegated.failure() == null
                    ? "CHAIN_WORKER_FAILED"
                    : delegated.failure().reason();
                return WorkerAttempt.failed(new SpecialistChainWorkerTrace(
                    prepared.target.specialistId().toString(),
                    "DELEGATION",
                    delegated.targetExecution() == null
                        ? null
                        : delegated.targetExecution().invocationId(),
                    null,
                    mapWorkerStatus(delegated.status()),
                    List.of(),
                    reason,
                    startedAt,
                    clock.instant()
                ), delegated.failure() == null
                    ? "The selected specialist did not return a safe result."
                    : delegated.failure().publicMessage(),
                    delegated.failure() != null
                        && delegated.failure().retryable());
            }
            return projectWorker(
                prepared,
                delegated.targetExecution(),
                "DELEGATION",
                startedAt
            );
        } catch (RuntimeException ex) {
            return WorkerAttempt.failed(new SpecialistChainWorkerTrace(
                prepared.target.specialistId().toString(),
                "DELEGATION",
                null,
                null,
                SpecialistChainWorkerStatus.FAILED,
                List.of(),
                "CHAIN_WORKER_INVOCATION_FAILED",
                startedAt,
                clock.instant()
            ), "The selected specialist could not be invoked safely.", true);
        }
    }

    private WorkerAttempt handoffWorker(
        SpecialistChainExecutionRecord record,
        SpecialistChainExecutionRequest<Object> request,
        AIExecutionResult<SpecialistChainDirective> managerExecution,
        PreparedWorker prepared,
        int decisionIndex
    ) {
        Instant startedAt = clock.instant();
        try {
            metrics.modelCall(
                record.chainId(),
                "handoff-successor",
                prepared.target.specialistId().toString()
            );
            SpecialistHandoffResult<SpecialistChainDirective, Object>
                handedOff = handoffGateway.handoff(
                    new SpecialistHandoffRequest<>(
                        managerExecution,
                        prepared.target.specialistId(),
                        prepared.targetInput,
                        request.trustedExecutionContext(),
                        record.deadline(),
                        childIdempotencyKey(
                            record,
                            decisionIndex,
                            prepared.target.specialistId(),
                            null,
                            "handoff"
                        )
                    ),
                    prepared.target.inputMapper().targetInputType(),
                    prepared.target.resultProjector().targetOutputType()
                );
            if (!handedOff.succeeded()) {
                String reason = handedOff.failure() == null
                    ? "CHAIN_HANDOFF_FAILED"
                    : handedOff.failure().reason();
                return WorkerAttempt.failed(new SpecialistChainWorkerTrace(
                    prepared.target.specialistId().toString(),
                    "HANDOFF",
                    handedOff.successorExecution() == null
                        ? null
                        : handedOff.successorExecution().invocationId(),
                    null,
                    mapWorkerStatus(handedOff.status()),
                    List.of(),
                    reason,
                    startedAt,
                    clock.instant()
                ), handedOff.failure() == null
                    ? "The terminal handoff did not return a safe result."
                    : handedOff.failure().publicMessage(),
                    handedOff.failure() != null
                        && handedOff.failure().retryable());
            }
            return projectWorker(
                prepared,
                handedOff.successorExecution(),
                "HANDOFF",
                startedAt
            );
        } catch (RuntimeException ex) {
            return WorkerAttempt.failed(new SpecialistChainWorkerTrace(
                prepared.target.specialistId().toString(),
                "HANDOFF",
                null,
                null,
                SpecialistChainWorkerStatus.FAILED,
                List.of(),
                "CHAIN_HANDOFF_INVOCATION_FAILED",
                startedAt,
                clock.instant()
            ), "The terminal handoff could not be invoked safely.", true);
        }
    }

    private WorkerAttempt projectWorker(
        PreparedWorker prepared,
        AIExecutionResult<Object> execution,
        String relationship,
        Instant startedAt
    ) {
        try {
            SpecialistChainResultProjection projection =
                prepared.target.resultProjector().project(
                    prepared.chainInput,
                    execution
                );
            validateEvidenceProjection(projection, execution.evidence());
            String resultHash = canonicalJson.hashValue(Map.of(
                "specialist",
                prepared.target.specialistId().toString(),
                "workerInvocationId",
                execution.invocationId(),
                "projection",
                projection
            ));
            SpecialistChainResultView view =
                new SpecialistChainResultView(
                    "chain-result-" + resultHash.substring(0, 24),
                    prepared.target.specialistId().toString(),
                    execution.invocationId(),
                    projection.summary(),
                    projection.facts(),
                    projection.evidenceReferenceIds(),
                    resultHash,
                    execution.completedAt()
                );
            return WorkerAttempt.succeeded(
                view,
                new SpecialistChainWorkerTrace(
                    prepared.target.specialistId().toString(),
                    relationship,
                    execution.invocationId(),
                    view.resultId(),
                    SpecialistChainWorkerStatus.SUCCEEDED,
                    projection.evidenceReferenceIds(),
                    null,
                    startedAt,
                    clock.instant()
                ),
                projectedCharacters(projection)
            );
        } catch (RuntimeException ex) {
            return WorkerAttempt.failed(new SpecialistChainWorkerTrace(
                prepared.target.specialistId().toString(),
                relationship,
                execution.invocationId(),
                null,
                SpecialistChainWorkerStatus.INVALID,
                List.of(),
                "CHAIN_RESULT_PROJECTION_INVALID",
                startedAt,
                clock.instant()
            ), "The specialist result could not be projected safely.", false);
        }
    }

    private void validateEvidenceProjection(
        SpecialistChainResultProjection projection,
        List<AIEvidenceReference> evidence
    ) {
        Set<String> allowed = new HashSet<>();
        evidence.forEach(reference -> allowed.add(reference.evidenceId()));
        if (!allowed.containsAll(projection.evidenceReferenceIds())) {
            throw new IllegalArgumentException(
                "Projected evidence must come from the worker result"
            );
        }
    }

    private boolean continueAfterWorkers(
        ExecutionCursor cursor,
        SpecialistChainDefinition<?> definition,
        List<WorkerAttempt> attempts,
        String parallelGroupId
    ) {
        List<SpecialistChainResultView> results = new ArrayList<>(
            cursor.checkpoint.projectedResults()
        );
        List<SpecialistChainWorkerTrace> traces = new ArrayList<>();
        Map<String, Integer> counts = new LinkedHashMap<>(
            cursor.checkpoint.targetInvocationCounts()
        );
        int additionalCharacters = 0;
        for (WorkerAttempt attempt : attempts) {
            results.add(attempt.result);
            traces.add(attempt.trace);
            counts.merge(attempt.result.specialist(), 1, Integer::sum);
            additionalCharacters += attempt.projectedCharacters;
        }
        int totalCharacters =
            cursor.checkpoint.projectedResultCharacters()
                + additionalCharacters;
        if (totalCharacters
            > definition.limits().maxProjectedResultCharacters()) {
            terminal(
                cursor,
                failedResult(
                    cursor.record,
                    cursor.record.chainContentHash(),
                    SpecialistChainExecutionStatus.FAILED,
                    "CHAIN_PROJECTED_RESULT_BUDGET_EXCEEDED",
                    "The specialist results exceed the approved manager-view budget.",
                    false,
                    cursor.checkpoint
                )
            );
            return false;
        }
        SpecialistChainManagerExecutionCheckpoint manager =
            cursor.checkpoint.managerExecution();
        SpecialistChainStepTrace step = new SpecialistChainStepTrace(
            cursor.checkpoint.managerDecisionCount() - 1,
            manager.invocationId(),
            manager.directive().type(),
            manager.directive().reason(),
            parallelGroupId,
            traces,
            remainingBudget(
                definition,
                cursor,
                attempts.size(),
                additionalCharacters
            ),
            manager.startedAt(),
            clock.instant()
        );
        List<SpecialistChainStepTrace> steps = new ArrayList<>(
            cursor.checkpoint.steps()
        );
        steps.add(step);
        SpecialistChainCheckpoint ready = copyCheckpoint(
            cursor.checkpoint,
            SpecialistChainCheckpointPhase.READY_FOR_MANAGER,
            results,
            steps,
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount() + attempts.size(),
            totalCharacters,
            counts,
            cursor.checkpoint.lastDirectiveHash(),
            null,
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        return checkpoint(cursor, ready);
    }

    private boolean finishWithMessage(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        SpecialistChainExecutionRequest<Object> request,
        SpecialistChainDirective directive,
        SpecialistChainExecutionStatus status,
        SpecialistId handoffTarget,
        List<WorkerAttempt> workers,
        ApprovedConversationSnapshot snapshot
    ) {
        String message = status == SpecialistChainExecutionStatus.HANDED_OFF
            ? workers.getFirst().result.summary()
            : directive.message();
        List<SpecialistChainResultView> results = new ArrayList<>(
            cursor.checkpoint.projectedResults()
        );
        List<SpecialistChainWorkerTrace> traces = new ArrayList<>();
        workers.forEach(worker -> {
            results.add(worker.result);
            traces.add(worker.trace);
        });
        SpecialistChainManagerExecutionCheckpoint manager =
            cursor.checkpoint.managerExecution();
        int workerCharacters = workers.stream()
            .mapToInt(value -> value.projectedCharacters)
            .sum();
        SpecialistChainStepTrace step = new SpecialistChainStepTrace(
            cursor.checkpoint.managerDecisionCount() - 1,
            manager.invocationId(),
            directive.type(),
            directive.reason(),
            null,
            traces,
            remainingBudget(
                registered.definition(),
                cursor,
                workers.size(),
                workerCharacters
            ),
            manager.startedAt(),
            clock.instant()
        );
        List<SpecialistChainStepTrace> steps = new ArrayList<>(
            cursor.checkpoint.steps()
        );
        steps.add(step);
        SpecialistChainCheckpoint terminalCheckpoint = copyCheckpoint(
            cursor.checkpoint,
            SpecialistChainCheckpointPhase.DIRECTIVE_ACCEPTED,
            results,
            steps,
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount() + workers.size(),
            cursor.checkpoint.projectedResultCharacters()
                + workerCharacters,
            incrementCounts(
                cursor.checkpoint.targetInvocationCounts(),
                workers
            ),
            cursor.checkpoint.lastDirectiveHash(),
            manager,
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        if (!checkpoint(cursor, terminalCheckpoint)) {
            return false;
        }
        if (request.conversationBinding() != null) {
            try {
                conversationRecorder.record(
                    request.conversationBinding(),
                    cursor.checkpoint.currentUserMessage(),
                    message,
                    recordingMetadata(
                        registered,
                        status,
                        cursor.checkpoint,
                        snapshot
                    )
                );
            } catch (RuntimeException ex) {
                terminal(
                    cursor,
                    failedResult(
                        cursor.record,
                        registered.contentHash(),
                        SpecialistChainExecutionStatus.FAILED,
                        "CHAIN_CONVERSATION_RECORDING_FAILED",
                        "The validated chain result could not be recorded safely.",
                        true,
                        cursor.checkpoint
                    )
                );
                return false;
            }
        }
        SpecialistChainExecutionResult result =
            new SpecialistChainExecutionResult(
                cursor.record.executionId(),
                registered.id(),
                registered.contentHash(),
                status,
                message,
                handoffTarget,
                List.copyOf(results),
                List.copyOf(steps),
                cursor.checkpoint.conversationSnapshotRevision(),
                cursor.checkpoint.conversationSourceTurnCount(),
                null,
                false,
                durable,
                cursor.record.createdAt(),
                clock.instant()
            );
        return terminal(cursor, result);
    }

    private Map<String, Integer> incrementCounts(
        Map<String, Integer> current,
        List<WorkerAttempt> workers
    ) {
        Map<String, Integer> updated = new LinkedHashMap<>(current);
        workers.forEach(worker -> updated.merge(
            worker.result.specialist(),
            1,
            Integer::sum
        ));
        return Map.copyOf(updated);
    }

    private Map<String, Object> recordingMetadata(
        RegisteredSpecialistChain registered,
        SpecialistChainExecutionStatus status,
        SpecialistChainCheckpoint checkpoint,
        ApprovedConversationSnapshot snapshot
    ) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("specialistChain", true);
        metadata.put("chainId", registered.id().toString());
        metadata.put("chainContentHash", registered.contentHash());
        metadata.put("chainStatus", status.name());
        metadata.put(
            "managerSpecialist",
            registered.definition().managerSpecialistId().toString()
        );
        metadata.put(
            "resultIds",
            checkpoint.projectedResults().stream()
                .map(SpecialistChainResultView::resultId)
                .toList()
        );
        metadata.put(
            "supportingResultIds",
            checkpoint.managerExecution() == null
                ? List.of()
                : checkpoint.managerExecution().directive()
                    .supportingResultIds()
        );
        metadata.put("decisionCount", checkpoint.managerDecisionCount());
        metadata.put("workerCount", checkpoint.workerInvocationCount());
        if (snapshot != null) {
            metadata.put(
                "interactionTurnId",
                snapshot.interactionTurnId()
            );
            metadata.put(
                "conversationSnapshotRevision",
                snapshot.revision()
            );
        }
        return Map.copyOf(metadata);
    }

    private boolean failWorkerGroup(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        List<WorkerAttempt> attempts
    ) {
        List<SpecialistChainWorkerTrace> traces = attempts.stream()
            .filter(Objects::nonNull)
            .map(WorkerAttempt::trace)
            .toList();
        WorkerAttempt failedAttempt = attempts.stream()
            .filter(Objects::nonNull)
            .filter(attempt -> !attempt.succeeded())
            .findFirst()
            .orElse(null);
        String reason = failedAttempt == null
            ? "CHAIN_REQUIRED_WORKER_FAILED"
            : failedAttempt.trace.failureReason();
        String publicMessage = failedAttempt == null
            ? "A required specialist did not return a safe result."
            : failedAttempt.publicFailure;
        boolean retryable = failedAttempt != null
            && failedAttempt.retryable;
        SpecialistChainManagerExecutionCheckpoint manager =
            cursor.checkpoint.managerExecution();
        SpecialistChainStepTrace step = new SpecialistChainStepTrace(
            cursor.checkpoint.managerDecisionCount() - 1,
            manager.invocationId(),
            manager.directive().type(),
            manager.directive().reason(),
            manager.directive().type()
                    == SpecialistChainDirectiveType.INVOKE_PARALLEL
                ? "failed-parallel-"
                    + cursor.checkpoint.managerDecisionCount()
                : null,
            traces,
            remainingBudget(registered.definition(), cursor),
            manager.startedAt(),
            clock.instant()
        );
        SpecialistChainCheckpoint withFailureTrace = copyCheckpoint(
            cursor.checkpoint,
            cursor.checkpoint.phase(),
            cursor.checkpoint.projectedResults(),
            append(cursor.checkpoint.steps(), step),
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount(),
            cursor.checkpoint.projectedResultCharacters(),
            cursor.checkpoint.targetInvocationCounts(),
            cursor.checkpoint.lastDirectiveHash(),
            cursor.checkpoint.managerExecution(),
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        );
        checkpoint(cursor, withFailureTrace);
        return terminal(
            cursor,
            failedResult(
                cursor.record,
                registered.contentHash(),
                SpecialistChainExecutionStatus.FAILED,
                reason,
                publicMessage,
                retryable,
                cursor.checkpoint
            )
        );
    }

    private boolean failParallelDeadline(
        ExecutionCursor cursor,
        RegisteredSpecialistChain registered,
        WorkerAttempt[] attempts
    ) {
        List<SpecialistChainWorkerTrace> traces = traces(attempts);
        SpecialistChainManagerExecutionCheckpoint manager =
            cursor.checkpoint.managerExecution();
        SpecialistChainStepTrace step = new SpecialistChainStepTrace(
            cursor.checkpoint.managerDecisionCount() - 1,
            manager.invocationId(),
            SpecialistChainDirectiveType.INVOKE_PARALLEL,
            manager.directive().reason(),
            "deadline-parallel-"
                + cursor.checkpoint.managerDecisionCount(),
            traces,
            remainingBudget(registered.definition(), cursor),
            manager.startedAt(),
            clock.instant()
        );
        checkpoint(cursor, copyCheckpoint(
            cursor.checkpoint,
            cursor.checkpoint.phase(),
            cursor.checkpoint.projectedResults(),
            append(cursor.checkpoint.steps(), step),
            cursor.checkpoint.managerDecisionCount(),
            cursor.checkpoint.workerInvocationCount(),
            cursor.checkpoint.projectedResultCharacters(),
            cursor.checkpoint.targetInvocationCounts(),
            cursor.checkpoint.lastDirectiveHash(),
            cursor.checkpoint.managerExecution(),
            cursor.checkpoint.conversationSnapshotRevision(),
            cursor.checkpoint.conversationSourceTurnCount()
        ));
        return terminal(
            cursor,
            failedResult(
                cursor.record,
                registered.contentHash(),
                SpecialistChainExecutionStatus.DEADLINE_EXCEEDED,
                "CHAIN_DEADLINE_EXCEEDED",
                "The parallel specialist group exceeded the chain deadline.",
                true,
                cursor.checkpoint
            )
        );
    }

    private boolean failDeadline(
        ExecutionCursor cursor,
        String chainContentHash
    ) {
        return terminal(
            cursor,
            failedResult(
                cursor.record,
                chainContentHash,
                SpecialistChainExecutionStatus.DEADLINE_EXCEEDED,
                "CHAIN_DEADLINE_EXCEEDED",
                "The specialist-chain deadline elapsed.",
                true,
                cursor.checkpoint
            )
        );
    }

    private List<SpecialistChainWorkerTrace> traces(
        WorkerAttempt[] attempts
    ) {
        List<SpecialistChainWorkerTrace> traces = new ArrayList<>();
        for (WorkerAttempt attempt : attempts) {
            if (attempt != null) {
                traces.add(attempt.trace);
            }
        }
        return List.copyOf(traces);
    }

    private List<SpecialistChainStepTrace> append(
        List<SpecialistChainStepTrace> values,
        SpecialistChainStepTrace value
    ) {
        List<SpecialistChainStepTrace> result = new ArrayList<>(values);
        result.add(value);
        return List.copyOf(result);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private PreparedWorker prepareWorker(
        SpecialistChainDefinition<Object> definition,
        Object chainInput,
        SpecialistChainTargetRequest request
    ) {
        SpecialistChainTarget target = findTarget(
            definition,
            request.specialistId()
        );
        Object targetInput = Objects.requireNonNull(
            target.inputMapper().map(chainInput, request),
            "target input is required"
        );
        if (!target.inputMapper().targetInputType().isInstance(targetInput)) {
            throw new IllegalArgumentException(
                "Mapped target input does not satisfy its registered type"
            );
        }
        return new PreparedWorker(target, chainInput, targetInput);
    }

    private SpecialistChainTarget<?, ?, ?> findTarget(
        SpecialistChainDefinition<?> definition,
        SpecialistId targetId
    ) {
        return definition.targets().stream()
            .filter(target -> target.specialistId().equals(targetId))
            .findFirst()
            .orElse(null);
    }

    private int targetIndex(
        SpecialistChainDefinition<?> definition,
        SpecialistId targetId
    ) {
        for (int index = 0; index < definition.targets().size(); index++) {
            if (definition.targets().get(index).specialistId()
                .equals(targetId)) {
                return index;
            }
        }
        return Integer.MAX_VALUE;
    }

    private AIExecutionResult<SpecialistChainDirective> managerExecution(
        RegisteredSpecialistChain registered,
        ExecutionCursor cursor
    ) {
        SpecialistChainManagerExecutionCheckpoint manager =
            cursor.checkpoint.managerExecution();
        return new AIExecutionResult<>(
            manager.invocationId(),
            registered.definition().managerSpecialistId(),
            AIExecutionStatus.SUCCEEDED,
            manager.directive(),
            List.of(),
            Map.of(
                "specialistContentHash",
                registered.managerContentHash(),
                "executionDeadline",
                cursor.record.deadline().toString()
            ),
            null,
            manager.startedAt(),
            manager.completedAt()
        );
    }

    private SpecialistChainBudgetView remainingBudget(
        SpecialistChainDefinition<?> definition,
        ExecutionCursor cursor
    ) {
        return remainingBudget(definition, cursor, 0, 0);
    }

    private SpecialistChainBudgetView remainingBudget(
        SpecialistChainDefinition<?> definition,
        ExecutionCursor cursor,
        int pendingWorkers,
        int pendingCharacters
    ) {
        return new SpecialistChainBudgetView(
            Math.max(
                0,
                definition.limits().maxManagerDecisions()
                    - cursor.checkpoint.managerDecisionCount()
            ),
            Math.max(
                0,
                definition.limits().maxWorkerInvocations()
                    - cursor.checkpoint.workerInvocationCount()
                    - pendingWorkers
            ),
            definition.limits().maxParallelWorkers(),
            Math.max(
                0,
                definition.limits().maxProjectedResultCharacters()
                    - cursor.checkpoint.projectedResultCharacters()
                    - pendingCharacters
            ),
            Math.max(
                0,
                Duration.between(
                    clock.instant(),
                    cursor.record.deadline()
                ).toMillis()
            )
        );
    }

    private int projectedCharacters(
        SpecialistChainResultProjection projection
    ) {
        int characters = projection.summary().length();
        for (Map.Entry<String, String> fact : projection.facts().entrySet()) {
            characters += fact.getKey().length() + fact.getValue().length();
        }
        for (String evidence : projection.evidenceReferenceIds()) {
            characters += evidence.length();
        }
        return characters;
    }

    private boolean checkpoint(
        ExecutionCursor cursor,
        SpecialistChainCheckpoint checkpoint
    ) {
        try {
            String protectedCheckpoint = codec.protectCheckpoint(
                cursor.record.executionId(),
                checkpoint
            );
            Instant now = clock.instant();
            SpecialistChainExecutionRecord updated =
                cursor.record.checkpointed(
                    protectedCheckpoint,
                    checkpoint.managerDecisionCount(),
                    now,
                    now.plus(leaseDuration)
                );
            if (!repository.compareAndSet(cursor.record, updated)) {
                return false;
            }
            cursor.record = updated;
            cursor.checkpoint = checkpoint;
            return true;
        } catch (RuntimeException ex) {
            log.warn(
                "Specialist chain {} checkpoint failed: {}",
                cursor.record.executionId(),
                ex.getClass().getSimpleName()
            );
            return false;
        }
    }

    private boolean terminal(
        ExecutionCursor cursor,
        SpecialistChainExecutionResult result
    ) {
        if (cursor.record.status().terminal()) {
            return false;
        }
        if (!complete(cursor.record, result)) {
            return false;
        }
        SpecialistChainExecutionRecord updated = repository
            .findById(cursor.record.executionId())
            .orElse(cursor.record);
        cursor.record = updated;
        metrics.terminal(
            result.chainId(),
            result.status(),
            Duration.between(result.startedAt(), result.completedAt())
        );
        return true;
    }

    private boolean complete(
        SpecialistChainExecutionRecord current,
        SpecialistChainExecutionResult result
    ) {
        try {
            String protectedResult = codec.protectResult(
                current.executionId(),
                result
            );
            SpecialistChainExecutionRecord completed = current.completed(
                result.status(),
                protectedResult,
                result.failure() == null
                    ? null
                    : result.failure().reason(),
                result.completedAt(),
                retention
            );
            return repository.compareAndSet(current, completed);
        } catch (RuntimeException ex) {
            log.error(
                "Specialist chain {} terminal result could not be persisted",
                current.executionId()
            );
            return false;
        }
    }

    private boolean terminalize(
        SpecialistChainExecutionRecord record,
        SpecialistChainExecutionStatus status,
        String reason,
        String message,
        boolean retryable
    ) {
        if (record.status().terminal()) {
            return false;
        }
        SpecialistChainExecutionResult result = failedResult(
            record,
            record.chainContentHash(),
            status,
            reason,
            message,
            retryable,
            safeCheckpoint(record)
        );
        boolean completed = complete(record, result);
        if (completed) {
            metrics.terminal(
                record.chainId(),
                status,
                Duration.between(record.createdAt(), result.completedAt())
            );
        }
        return completed;
    }

    private SpecialistChainCheckpoint safeCheckpoint(
        SpecialistChainExecutionRecord record
    ) {
        try {
            return codec.unprotectCheckpoint(record);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private SpecialistChainExecutionResult failedResult(
        SpecialistChainExecutionRecord record,
        String chainHash,
        SpecialistChainExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        SpecialistChainCheckpoint checkpoint
    ) {
        return new SpecialistChainExecutionResult(
            record.executionId(),
            record.chainId(),
            chainHash,
            status,
            null,
            null,
            checkpoint == null
                ? List.of()
                : checkpoint.projectedResults(),
            checkpoint == null ? List.of() : checkpoint.steps(),
            checkpoint == null
                ? null
                : checkpoint.conversationSnapshotRevision(),
            checkpoint == null
                ? 0
                : checkpoint.conversationSourceTurnCount(),
            new SpecialistChainFailure(reason, message, retryable),
            false,
            durable,
            record.createdAt(),
            clock.instant()
        );
    }

    private Submission rejectedSubmission(
        String executionId,
        ai.fabric.execution.chain.SpecialistChainId chainId,
        String chainHash,
        SpecialistChainExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        Instant startedAt,
        Instant requestedDeadline
    ) {
        Instant now = clock.instant();
        SpecialistChainExecutionResult result =
            new SpecialistChainExecutionResult(
                executionId,
                chainId,
                chainHash,
                status,
                null,
                null,
                List.of(),
                List.of(),
                null,
                0,
                new SpecialistChainFailure(reason, message, retryable),
                false,
                durable,
                startedAt,
                now
            );
        return new Submission(null, result, false);
    }

    private SpecialistChainExecutionResult awaitTerminal(
        String executionId,
        TrustedExecutionContext context,
        Instant deadline,
        boolean replayed
    ) {
        while (clock.instant().isBefore(deadline)) {
            SpecialistChainExecutionRecord record = repository
                .findById(executionId)
                .orElse(null);
            if (record == null || !authorized(record, context)) {
                return missingResult(executionId, deadline);
            }
            if (record.status().terminal()) {
                try {
                    SpecialistChainExecutionResult result =
                        codec.unprotectResult(record);
                    return replayed ? result.asReplayed() : result;
                } catch (RuntimeException ex) {
                    return failedResult(
                        record,
                        record.chainContentHash(),
                        SpecialistChainExecutionStatus.FAILED,
                        "CHAIN_RESULT_UNAVAILABLE",
                        "The stored specialist-chain result could not be verified.",
                        false,
                        safeCheckpoint(record)
                    );
                }
            }
            try {
                Thread.sleep(POLL_INTERVAL.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                cancel(executionId, context);
                SpecialistChainExecutionRecord current = repository
                    .findById(executionId)
                    .orElse(record);
                return failedResult(
                    current,
                    current.chainContentHash(),
                    SpecialistChainExecutionStatus.CANCELLED,
                    "CHAIN_CANCELLED",
                    "The specialist chain was cancelled.",
                    true,
                    safeCheckpoint(current)
                );
            }
        }
        return expireAwaitedExecution(executionId, context, deadline);
    }

    private SpecialistChainExecutionResult expireAwaitedExecution(
        String executionId,
        TrustedExecutionContext context,
        Instant deadline
    ) {
        SpecialistChainExecutionRecord current = repository
            .findById(executionId)
            .filter(record -> authorized(record, context))
            .orElse(null);
        if (current == null) {
            return missingResult(executionId, deadline);
        }
        if (!current.status().terminal()) {
            SpecialistChainExecutionResult timeout = failedResult(
                current,
                current.chainContentHash(),
                SpecialistChainExecutionStatus.DEADLINE_EXCEEDED,
                "CHAIN_DEADLINE_EXCEEDED",
                "The specialist-chain deadline elapsed.",
                true,
                safeCheckpoint(current)
            );
            if (complete(current, timeout)) {
                metrics.terminal(
                    current.chainId(),
                    timeout.status(),
                    Duration.between(
                        timeout.startedAt(),
                        timeout.completedAt()
                    )
                );
                ActiveExecution active = activeExecutions.remove(
                    executionId
                );
                if (active != null) {
                    active.cancel();
                }
            }
        }
        SpecialistChainExecutionRecord stored = repository
            .findById(executionId)
            .filter(record -> authorized(record, context))
            .orElse(null);
        if (stored == null) {
            return missingResult(executionId, deadline);
        }
        if (!stored.status().terminal()) {
            return failedResult(
                stored,
                stored.chainContentHash(),
                SpecialistChainExecutionStatus.FAILED,
                "CHAIN_STATE_UNAVAILABLE",
                "The specialist-chain terminal state could not be persisted safely.",
                true,
                safeCheckpoint(stored)
            );
        }
        try {
            return codec.unprotectResult(stored);
        } catch (RuntimeException ex) {
            return failedResult(
                stored,
                stored.chainContentHash(),
                SpecialistChainExecutionStatus.FAILED,
                "CHAIN_RESULT_UNAVAILABLE",
                "The stored specialist-chain result could not be verified.",
                false,
                safeCheckpoint(stored)
            );
        }
    }

    private SpecialistChainExecutionResult missingResult(
        String executionId,
        Instant deadline
    ) {
        Instant now = clock.instant();
        ai.fabric.execution.chain.SpecialistChainId unknown =
            ai.fabric.execution.chain.SpecialistChainId.of(
                "unknown-chain",
                "0"
            );
        return new SpecialistChainExecutionResult(
            executionId,
            unknown,
            unknownHash(unknown),
            SpecialistChainExecutionStatus.FAILED,
            null,
            null,
            List.of(),
            List.of(),
            null,
            0,
            new SpecialistChainFailure(
                "CHAIN_RESULT_UNAVAILABLE",
                "The specialist-chain result is unavailable.",
                false
            ),
            false,
            durable,
            now,
            now
        );
    }

    private SpecialistChainExecutionSnapshot snapshot(
        SpecialistChainExecutionRecord record
    ) {
        SpecialistChainCheckpoint checkpoint = safeCheckpoint(record);
        SpecialistChainFailure failure =
            record.status().terminal() && !record.status().succeeded()
                ? new SpecialistChainFailure(
                    record.failureReason() == null
                        ? "CHAIN_FAILED"
                        : record.failureReason(),
                    "The specialist chain did not complete successfully.",
                    false
                )
                : null;
        return new SpecialistChainExecutionSnapshot(
            record.executionId(),
            record.chainId(),
            record.status(),
            record.nextDecisionIndex(),
            checkpoint == null ? List.of() : checkpoint.steps(),
            failure,
            durable,
            record.createdAt(),
            record.updatedAt(),
            record.deadline()
        );
    }

    private SpecialistChainExecutionHandle handle(
        SpecialistChainExecutionRecord record,
        boolean replayed
    ) {
        return new SpecialistChainExecutionHandle(
            record.executionId(),
            record.chainId(),
            record.status(),
            replayed,
            durable,
            record.failureReason(),
            record.createdAt(),
            record.deadline()
        );
    }

    private SpecialistChainExecutionHandle handle(
        SpecialistChainExecutionResult result
    ) {
        return new SpecialistChainExecutionHandle(
            result.executionId(),
            result.chainId(),
            result.status(),
            result.replayed(),
            result.durable(),
            result.failure() == null ? null : result.failure().reason(),
            result.startedAt(),
            result.completedAt()
        );
    }

    private boolean authorized(
        SpecialistChainExecutionRecord record,
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

    private <I> String requestFingerprint(
        SpecialistChainExecutionRequest<I> request,
        RegisteredSpecialistChain registered
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("chain", registered.id().toString());
        value.put("chainContentHash", registered.contentHash());
        value.put("managerContentHash", registered.managerContentHash());
        value.put("input", request.input());
        value.put("conversationBinding", request.conversationBinding());
        value.put(
            "requestedDeadline",
            request.deadline() == null
                ? "chainMaximum"
                : request.deadline().toString()
        );
        return security.canonicalHash(value);
    }

    private String directiveStateHash(
        SpecialistChainDirective directive,
        List<SpecialistChainResultView> results
    ) {
        return canonicalJson.hashValue(Map.of(
            "directive",
            directive,
            "resultHashes",
            results.stream()
                .map(SpecialistChainResultView::resultHash)
                .toList()
        ));
    }

    private String managerIdempotencyKey(
        SpecialistChainExecutionRecord record,
        int decisionIndex,
        int correctionAttempt
    ) {
        return "chain-manager:" + CanonicalJsonSupport.sha256(
            record.executionId() + "\n" + record.chainContentHash()
                + "\n" + decisionIndex + "\n" + correctionAttempt
        );
    }

    private String childIdempotencyKey(
        SpecialistChainExecutionRecord record,
        int decisionIndex,
        SpecialistId target,
        String parallelGroupId,
        String relationship
    ) {
        return "chain-" + relationship + ":"
            + CanonicalJsonSupport.sha256(
                record.executionId() + "\n" + record.chainContentHash()
                    + "\n" + decisionIndex + "\n" + target + "\n"
                    + (parallelGroupId == null ? "single" : parallelGroupId)
            );
    }

    private Instant effectiveDeadline(
        Instant requested,
        Instant startedAt,
        Duration maximum
    ) {
        Instant allowed = startedAt.plus(maximum);
        return requested != null && requested.isBefore(allowed)
            ? requested
            : allowed;
    }

    private SpecialistChainCheckpoint copyCheckpoint(
        SpecialistChainCheckpoint source,
        SpecialistChainCheckpointPhase phase,
        List<SpecialistChainResultView> results,
        List<SpecialistChainStepTrace> steps,
        int decisions,
        int workers,
        int characters,
        Map<String, Integer> targetCounts,
        String lastDirectiveHash,
        SpecialistChainManagerExecutionCheckpoint managerExecution,
        String snapshotRevision,
        long sourceTurnCount
    ) {
        return new SpecialistChainCheckpoint(
            phase,
            source.currentUserMessage(),
            source.applicationContext(),
            results,
            steps,
            decisions,
            workers,
            characters,
            targetCounts,
            lastDirectiveHash,
            managerExecution,
            snapshotRevision,
            sourceTurnCount
        );
    }

    private SpecialistChainFailure failure(
        String reason,
        String message,
        boolean retryable
    ) {
        return new SpecialistChainFailure(reason, message, retryable);
    }

    private SpecialistChainExecutionStatus mapStatus(
        AIExecutionStatus status
    ) {
        return switch (status) {
            case INVALID -> SpecialistChainExecutionStatus.INVALID;
            case DENIED -> SpecialistChainExecutionStatus.DENIED;
            case DEADLINE_EXCEEDED ->
                SpecialistChainExecutionStatus.DEADLINE_EXCEEDED;
            case CANCELLED -> SpecialistChainExecutionStatus.CANCELLED;
            default -> SpecialistChainExecutionStatus.FAILED;
        };
    }

    private SpecialistChainWorkerStatus mapWorkerStatus(
        AIExecutionStatus status
    ) {
        return switch (status) {
            case INVALID -> SpecialistChainWorkerStatus.INVALID;
            case DENIED -> SpecialistChainWorkerStatus.DENIED;
            case DEADLINE_EXCEEDED ->
                SpecialistChainWorkerStatus.DEADLINE_EXCEEDED;
            case CANCELLED -> SpecialistChainWorkerStatus.CANCELLED;
            default -> SpecialistChainWorkerStatus.FAILED;
        };
    }

    private boolean isUncertain(SpecialistChainCheckpointPhase phase) {
        return phase == SpecialistChainCheckpointPhase.MANAGER_IN_FLIGHT
            || phase == SpecialistChainCheckpointPhase.WORKERS_IN_FLIGHT
            || phase == SpecialistChainCheckpointPhase.HANDOFF_IN_FLIGHT;
    }

    private String uncertainReason(SpecialistChainCheckpointPhase phase) {
        return switch (phase) {
            case MANAGER_IN_FLIGHT -> "CHAIN_MANAGER_OUTCOME_UNCERTAIN";
            case WORKERS_IN_FLIGHT -> "CHAIN_WORKER_OUTCOME_UNCERTAIN";
            case HANDOFF_IN_FLIGHT -> "CHAIN_HANDOFF_OUTCOME_UNCERTAIN";
            default -> "CHAIN_OUTCOME_UNCERTAIN";
        };
    }

    private void cancel(List<? extends Future<?>> futures) {
        futures.forEach(future -> future.cancel(true));
    }

    @SuppressWarnings("unchecked")
    private <I> SpecialistChainDefinition<I> typedDefinition(
        RegisteredSpecialistChain registered,
        I input
    ) {
        if (!registered.definition().inputType().isInstance(input)) {
            throw new IllegalArgumentException(
                "Chain input type does not match"
            );
        }
        return (SpecialistChainDefinition<I>) registered.definition();
    }

    private String unknownHash(
        ai.fabric.execution.chain.SpecialistChainId id
    ) {
        return CanonicalJsonSupport.sha256("unknown-chain\n" + id);
    }

    private String executionId() {
        return "chain-" + UUID.randomUUID();
    }

    private static final class ExecutionCursor {
        private SpecialistChainExecutionRecord record;
        private SpecialistChainCheckpoint checkpoint;

        private ExecutionCursor(
            SpecialistChainExecutionRecord record,
            SpecialistChainCheckpoint checkpoint
        ) {
            this.record = record;
            this.checkpoint = checkpoint;
        }
    }

    @SuppressWarnings("rawtypes")
    private record PreparedWorker(
        SpecialistChainTarget target,
        Object chainInput,
        Object targetInput
    ) {}

    private record WorkerAttempt(
        SpecialistChainResultView result,
        SpecialistChainWorkerTrace trace,
        int projectedCharacters,
        String publicFailure,
        boolean retryable
    ) {
        private static WorkerAttempt succeeded(
            SpecialistChainResultView result,
            SpecialistChainWorkerTrace trace,
            int projectedCharacters
        ) {
            return new WorkerAttempt(
                result,
                trace,
                projectedCharacters,
                null,
                false
            );
        }

        private static WorkerAttempt failed(
            SpecialistChainWorkerTrace trace,
            String publicFailure,
            boolean retryable
        ) {
            return new WorkerAttempt(
                null,
                trace,
                0,
                publicFailure,
                retryable
            );
        }

        private boolean succeeded() {
            return result != null;
        }
    }

    private record IndexedWorkerAttempt(int index, WorkerAttempt attempt) {}

    private record Submission(
        SpecialistChainExecutionRecord record,
        SpecialistChainExecutionResult result,
        boolean replayed
    ) {}

    private static final class ActiveExecution {
        private volatile Future<?> root;
        private final Set<Future<?>> branches =
            ConcurrentHashMap.newKeySet();

        private void setRoot(Future<?> value) {
            root = value;
        }

        private void addBranch(Future<?> value) {
            branches.add(value);
        }

        private void removeBranches(List<? extends Future<?>> values) {
            branches.removeAll(values);
        }

        private void cancel() {
            Future<?> rootTask = root;
            if (rootTask != null) {
                rootTask.cancel(true);
            }
            branches.forEach(task -> task.cancel(true));
        }
    }

    public record RecoverySummary(
        int dispatched,
        int deadlineFailures,
        int attemptFailures,
        int deletedAfterRetention
    ) {}
}
