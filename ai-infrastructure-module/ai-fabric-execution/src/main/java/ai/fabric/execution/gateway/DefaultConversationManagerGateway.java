package ai.fabric.execution.gateway;

import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.delegation.SpecialistDelegationRequest;
import ai.fabric.execution.delegation.SpecialistDelegationResult;
import ai.fabric.execution.manager.ConversationManagerContextValue;
import ai.fabric.execution.manager.ConversationManagerDefinition;
import ai.fabric.execution.manager.ConversationManagerDirective;
import ai.fabric.execution.manager.ConversationManagerDirectiveType;
import ai.fabric.execution.manager.ConversationManagerFailure;
import ai.fabric.execution.manager.ConversationManagerGateway;
import ai.fabric.execution.manager.ConversationManagerId;
import ai.fabric.execution.manager.ConversationManagerInput;
import ai.fabric.execution.manager.ConversationManagerRegistry;
import ai.fabric.execution.manager.ConversationManagerTarget;
import ai.fabric.execution.manager.ConversationManagerTurnRequest;
import ai.fabric.execution.manager.ConversationManagerTurnResult;
import ai.fabric.execution.manager.ConversationManagerTurnStatus;
import ai.fabric.execution.manager.RegisteredConversationManager;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes one closed manager decision and zero or one read-only worker under
 * a shared interactive-turn lease.
 */
public final class DefaultConversationManagerGateway
    implements ConversationManagerGateway {

    private static final Logger log = LoggerFactory.getLogger(
        DefaultConversationManagerGateway.class
    );

    private final ConversationManagerRegistry managerRegistry;
    private final SpecialistClientFactory clientFactory;
    private final SpecialistDelegationGateway delegationGateway;
    private final AIExecutionConversationRecorder conversationRecorder;
    private final SharedInteractiveTurnCoordinator turnCoordinator;
    private final CanonicalJsonSupport canonicalJson;
    private final Clock clock;
    private final int maxActive;
    private final Duration resultTtl;
    private final Map<ReplayBinding, ReplayEntry> replays =
        new ConcurrentHashMap<>();

    public DefaultConversationManagerGateway(
        ConversationManagerRegistry managerRegistry,
        SpecialistClientFactory clientFactory,
        SpecialistDelegationGateway delegationGateway,
        AIExecutionConversationRecorder conversationRecorder,
        SharedInteractiveTurnCoordinator turnCoordinator,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        int maxActive,
        Duration resultTtl
    ) {
        this.managerRegistry = Objects.requireNonNull(
            managerRegistry,
            "managerRegistry is required"
        );
        this.clientFactory = Objects.requireNonNull(
            clientFactory,
            "clientFactory is required"
        );
        this.delegationGateway = Objects.requireNonNull(
            delegationGateway,
            "delegationGateway is required"
        );
        this.conversationRecorder = Objects.requireNonNull(
            conversationRecorder,
            "conversationRecorder is required"
        );
        this.turnCoordinator = Objects.requireNonNull(
            turnCoordinator,
            "turnCoordinator is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (maxActive <= 0) {
            throw new IllegalArgumentException(
                "maxActive must be positive"
            );
        }
        if (resultTtl == null
            || resultTtl.isZero()
            || resultTtl.isNegative()) {
            throw new IllegalArgumentException(
                "resultTtl must be positive"
            );
        }
        this.maxActive = maxActive;
        this.resultTtl = resultTtl;
    }

    @Override
    public <I> ConversationManagerTurnResult execute(
        ConversationManagerTurnRequest<I> request
    ) {
        Objects.requireNonNull(request, "request is required");
        cleanup();
        Instant startedAt = clock.instant();
        RegisteredConversationManager registered =
            managerRegistry.find(request.managerId()).orElse(null);
        if (registered == null) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_NOT_FOUND",
                "The requested conversation manager is not registered.",
                false,
                startedAt
            );
        }
        ConversationManagerDefinition<?> definition =
            registered.definition();
        if (!definition.inputType().isInstance(request.input())) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_INPUT_TYPE_INVALID",
                "The manager request does not satisfy its typed contract.",
                false,
                startedAt
            );
        }
        if (request.conversationBinding() == null
            || request.idempotencyKey() == null) {
            return executeTurn(
                request,
                registered,
                startedAt
            );
        }

        String fingerprint;
        try {
            fingerprint = fingerprint(request, registered);
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_FINGERPRINT_INVALID",
                "The manager request could not be snapshotted safely.",
                false,
                startedAt
            );
        }
        ReplayBinding replayBinding = new ReplayBinding(
            ExecutionAccessBinding.from(
                request.trustedExecutionContext()
            ),
            request.conversationBinding().userId(),
            request.conversationBinding().conversationId(),
            registered.id(),
            registered.contentHash(),
            request.idempotencyKey()
        );
        ReplayEntry candidate = new ReplayEntry(
            fingerprint,
            clock.instant().plus(resultTtl)
        );
        ReplayEntry entry;
        synchronized (replays) {
            entry = replays.get(replayBinding);
            if (entry == null) {
                if (replays.size() >= maxActive) {
                    return failure(
                        request.managerId(),
                        ConversationManagerTurnStatus.FAILED,
                        "MANAGER_CAPACITY_EXCEEDED",
                        "Conversation-manager capacity is temporarily exhausted.",
                        true,
                        startedAt
                    );
                }
                replays.put(replayBinding, candidate);
                entry = candidate;
            }
        }
        synchronized (entry) {
            if (!entry.fingerprint.equals(fingerprint)) {
                return failure(
                    request.managerId(),
                    ConversationManagerTurnStatus.INVALID,
                    "MANAGER_IDEMPOTENCY_CONFLICT",
                    "The manager idempotency key was already used for different work.",
                    false,
                    startedAt
                );
            }
            if (entry.result != null) {
                return entry.result.asReplayed();
            }
            ConversationManagerTurnResult result = executeTurn(
                request,
                registered,
                startedAt
            );
            entry.result = result;
            entry.expiresAt = clock.instant().plus(resultTtl);
            return result;
        }
    }

    private <I> ConversationManagerTurnResult executeTurn(
        ConversationManagerTurnRequest<I> request,
        RegisteredConversationManager registered,
        Instant startedAt
    ) {
        ConversationManagerDefinition<I> definition;
        try {
            definition = typedDefinition(registered, request.input());
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_INPUT_TYPE_INVALID",
                "The manager request does not satisfy its typed contract.",
                false,
                startedAt
            );
        }

        String userMessage;
        ConversationManagerInput managerInput;
        try {
            userMessage = definition.inputAdapter()
                .currentUserMessage(request.input());
            List<ConversationManagerContextValue> applicationContext =
                definition.inputAdapter()
                .applicationContext(request.input());
            managerInput = new ConversationManagerInput(
                userMessage,
                applicationContext,
                definition.targets().stream()
                    .map(ConversationManagerTarget::view)
                    .toList()
            );
            userMessage = managerInput.currentUserMessage();
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_INPUT_INVALID",
                "The application could not prepare safe manager input.",
                false,
                startedAt
            );
        }

        String approvedUserMessage = userMessage;
        SharedInteractiveTurnCoordinator.CoordinatedTurn<
            ConversationManagerTurnResult
        > coordinated;
        try {
            coordinated = turnCoordinator.coordinate(
                definition.managerSpecialistId(),
                request.trustedExecutionContext(),
                request.conversationBinding(),
                request.idempotencyKey(),
                SharedInteractiveTurnCoordinator
                    .RecordingPolicy.COORDINATED,
                turn -> invokeAndRecord(
                    request,
                    registered,
                    definition,
                    managerInput,
                    approvedUserMessage,
                    turn,
                    startedAt
                )
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Conversation manager {} failed before a safe result: {}",
                registered.id(),
                ex.getClass().getSimpleName()
            );
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.FAILED,
                "MANAGER_TURN_FAILED",
                "The conversation-manager turn could not be completed safely.",
                true,
                startedAt
            );
        }
        if (!coordinated.succeeded()) {
            AIExecutionFailure failure = coordinated.failure();
            return failure(
                request.managerId(),
                mapCoordinatorStatus(failure.reason()),
                failure.reason(),
                failure.publicMessage(),
                failure.retryable(),
                startedAt
            );
        }
        return coordinated.value();
    }

    private <I> ConversationManagerTurnResult invokeAndRecord(
        ConversationManagerTurnRequest<I> request,
        RegisteredConversationManager registered,
        ConversationManagerDefinition<I> definition,
        ConversationManagerInput managerInput,
        String userMessage,
        SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn turn,
        Instant startedAt
    ) {
        Instant deadline = effectiveDeadline(
            request.deadline(),
            startedAt,
            definition.maximumDuration()
        );
        if (!clock.instant().isBefore(deadline)) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.DEADLINE_EXCEEDED,
                "MANAGER_DEADLINE_EXCEEDED",
                "The conversation-manager deadline has elapsed.",
                true,
                startedAt,
                turn.snapshot()
            );
        }
        if (Thread.currentThread().isInterrupted()) {
            return cancelled(
                request.managerId(),
                startedAt,
                turn.snapshot(),
                null
            );
        }

        SpecialistClient<
            ConversationManagerInput,
            ConversationManagerDirective
        > client;
        try {
            client = clientFactory.bind(
                definition.managerSpecialistId(),
                ConversationManagerInput.class,
                ConversationManagerDirective.class
            );
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_BINDING_INVALID",
                "The manager specialist does not satisfy its typed contract.",
                false,
                startedAt,
                turn.snapshot()
            );
        }

        AIExecutionResult<ConversationManagerDirective> managerExecution;
        try {
            managerExecution = client.execute(new SpecialistInvocation<>(
                managerInput,
                request.trustedExecutionContext(),
                turn.approvedBinding(),
                deadline,
                managerIdempotencyKey(request, registered)
            ));
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.FAILED,
                "MANAGER_INVOCATION_FAILED",
                "The manager specialist could not be invoked safely.",
                true,
                startedAt,
                turn.snapshot()
            );
        }
        if (!managerExecution.succeeded()) {
            return managerExecutionFailure(
                request.managerId(),
                managerExecution,
                startedAt,
                turn.snapshot()
            );
        }
        if (Thread.currentThread().isInterrupted()) {
            return cancelled(
                request.managerId(),
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }

        ConversationManagerDirective directive =
            managerExecution.output();
        return switch (directive.type()) {
            case ASK_USER -> recordExternalResult(
                request,
                registered,
                ConversationManagerTurnStatus.ASKED_USER,
                directive.message(),
                null,
                managerExecution,
                null,
                userMessage,
                turn,
                startedAt
            );
            case COMPLETE -> recordExternalResult(
                request,
                registered,
                ConversationManagerTurnStatus.COMPLETED,
                directive.message(),
                null,
                managerExecution,
                null,
                userMessage,
                turn,
                startedAt
            );
            case INVOKE_SPECIALIST -> invokeTarget(
                request,
                registered,
                definition,
                directive,
                managerExecution,
                userMessage,
                turn,
                deadline,
                startedAt
            );
        };
    }

    private <I> ConversationManagerTurnResult invokeTarget(
        ConversationManagerTurnRequest<I> request,
        RegisteredConversationManager registered,
        ConversationManagerDefinition<I> definition,
        ConversationManagerDirective directive,
        AIExecutionResult<ConversationManagerDirective> managerExecution,
        String userMessage,
        SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn turn,
        Instant deadline,
        Instant startedAt
    ) {
        SpecialistId targetId;
        try {
            targetId = directive.requiredTarget();
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_DIRECTIVE_INVALID",
                "The manager returned an invalid directive.",
                false,
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }
        ConversationManagerTarget<I, ?, ?> target =
            definition.targets().stream()
                .filter(candidate ->
                    candidate.specialistId().equals(targetId)
                )
                .findFirst()
                .orElse(null);
        if (target == null) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.DENIED,
                "MANAGER_TARGET_NOT_ALLOWED",
                "The manager selected a specialist outside its approved target set.",
                false,
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }
        return invokeTargetUnchecked(
            request,
            registered,
            target,
            managerExecution,
            userMessage,
            turn,
            deadline,
            startedAt
        );
    }

    private <I, TI, TO> ConversationManagerTurnResult
        invokeTypedTargetInternal(
        ConversationManagerTurnRequest<I> request,
        RegisteredConversationManager registered,
        ConversationManagerTarget<I, TI, TO> target,
        AIExecutionResult<ConversationManagerDirective> managerExecution,
        String userMessage,
        SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn turn,
        Instant deadline,
        Instant startedAt
    ) {
        TI targetInput;
        if (Thread.currentThread().isInterrupted()) {
            return cancelled(
                request.managerId(),
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }
        try {
            targetInput = Objects.requireNonNull(
                target.inputMapper().map(request.input()),
                "target input is required"
            );
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_TARGET_INPUT_INVALID",
                "The application could not prepare safe specialist input.",
                false,
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }

        SpecialistDelegationResult<ConversationManagerDirective, TO>
            delegated;
        try {
            delegated = delegationGateway.delegate(
                new SpecialistDelegationRequest<>(
                    managerExecution,
                    target.specialistId(),
                    targetInput,
                    request.trustedExecutionContext(),
                    deadline,
                    targetIdempotencyKey(
                        request,
                        managerExecution,
                        target.specialistId()
                    )
                ),
                target.inputMapper().targetInputType(),
                target.resultProjector().targetOutputType()
            );
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.FAILED,
                "MANAGER_TARGET_INVOCATION_FAILED",
                "The selected specialist could not be invoked safely.",
                true,
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }
        if (!delegated.succeeded()) {
            var delegatedFailure = delegated.failure();
            return failure(
                request.managerId(),
                mapExecutionStatus(delegated.status()),
                delegatedFailure == null
                    ? "MANAGER_TARGET_FAILED"
                    : delegatedFailure.reason(),
                delegatedFailure == null
                    ? "The selected specialist did not return a safe result."
                    : delegatedFailure.publicMessage(),
                delegatedFailure != null
                    && delegatedFailure.retryable(),
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }
        if (Thread.currentThread().isInterrupted()) {
            return cancelled(
                request.managerId(),
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }

        AIExecutionResult<TO> targetExecution =
            delegated.targetExecution();
        String externalMessage;
        try {
            externalMessage = validateExternalMessage(
                target.resultProjector().project(
                    request.input(),
                    targetExecution
                )
            );
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_TARGET_PROJECTION_INVALID",
                "The selected specialist result could not be projected safely.",
                false,
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }
        return recordExternalResult(
            request,
            registered,
            ConversationManagerTurnStatus.SPECIALIST_RESULT,
            externalMessage,
            target.specialistId(),
            managerExecution,
            targetExecution,
            userMessage,
            turn,
            startedAt
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <I> ConversationManagerTurnResult invokeTargetUnchecked(
        ConversationManagerTurnRequest<I> request,
        RegisteredConversationManager registered,
        ConversationManagerTarget<I, ?, ?> target,
        AIExecutionResult<ConversationManagerDirective> managerExecution,
        String userMessage,
        SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn turn,
        Instant deadline,
        Instant startedAt
    ) {
        return invokeTypedTargetInternal(
            request,
            registered,
            (ConversationManagerTarget) target,
            managerExecution,
            userMessage,
            turn,
            deadline,
            startedAt
        );
    }

    private ConversationManagerTurnResult recordExternalResult(
        ConversationManagerTurnRequest<?> request,
        RegisteredConversationManager registered,
        ConversationManagerTurnStatus status,
        String externalMessage,
        SpecialistId target,
        AIExecutionResult<ConversationManagerDirective> managerExecution,
        AIExecutionResult<?> targetExecution,
        String userMessage,
        SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn turn,
        Instant startedAt
    ) {
        String message;
        try {
            message = validateExternalMessage(externalMessage);
            conversationRecorder.record(
                request.conversationBinding(),
                userMessage,
                message,
                recordingMetadata(
                    registered,
                    status,
                    managerExecution,
                    target,
                    targetExecution,
                    turn
                )
            );
        } catch (RuntimeException ex) {
            return failure(
                request.managerId(),
                ConversationManagerTurnStatus.FAILED,
                "MANAGER_CONVERSATION_RECORDING_FAILED",
                "The validated manager result could not be recorded safely.",
                true,
                startedAt,
                turn.snapshot(),
                managerExecution.invocationId()
            );
        }
        return new ConversationManagerTurnResult(
            managerTurnId(),
            request.managerId(),
            status,
            message,
            target,
            managerExecution.invocationId(),
            targetExecution == null
                ? null
                : targetExecution.invocationId(),
            turn.snapshot().revision(),
            turn.snapshot().sourceTurnCount(),
            null,
            false,
            startedAt,
            clock.instant()
        );
    }

    private Map<String, Object> recordingMetadata(
        RegisteredConversationManager registered,
        ConversationManagerTurnStatus status,
        AIExecutionResult<ConversationManagerDirective> managerExecution,
        SpecialistId target,
        AIExecutionResult<?> targetExecution,
        SharedInteractiveTurnCoordinator.ApprovedInteractiveTurn turn
    ) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("conversationManager", true);
        metadata.put("managerId", registered.id().toString());
        metadata.put("managerContentHash", registered.contentHash());
        metadata.put(
            "managerSpecialist",
            managerExecution.specialistId().toString()
        );
        metadata.put(
            "managerInvocationId",
            managerExecution.invocationId()
        );
        metadata.put("managerTurnStatus", status.name());
        metadata.put(
            "interactionTurnId",
            turn.snapshot().interactionTurnId()
        );
        metadata.put(
            "conversationSnapshotRevision",
            turn.snapshot().revision()
        );
        if (target != null) {
            metadata.put("selectedSpecialist", target.toString());
        }
        if (targetExecution != null) {
            metadata.put(
                "workerInvocationId",
                targetExecution.invocationId()
            );
        }
        return Map.copyOf(metadata);
    }

    private ConversationManagerTurnResult managerExecutionFailure(
        ConversationManagerId managerId,
        AIExecutionResult<?> managerExecution,
        Instant startedAt,
        ApprovedConversationSnapshot snapshot
    ) {
        AIExecutionFailure failure = managerExecution.failure();
        if (failure == null) {
            return failure(
                managerId,
                ConversationManagerTurnStatus.INVALID,
                "MANAGER_RESULT_UNSUPPORTED",
                "The manager returned an unsupported execution result.",
                false,
                startedAt,
                snapshot,
                managerExecution.invocationId()
            );
        }
        return failure(
            managerId,
            mapExecutionStatus(managerExecution.status()),
            failure.reason(),
            failure.publicMessage(),
            failure.retryable(),
            startedAt,
            snapshot,
            managerExecution.invocationId()
        );
    }

    private ConversationManagerTurnStatus mapCoordinatorStatus(
        String reason
    ) {
        return "CONVERSATION_BUSY".equals(reason)
            ? ConversationManagerTurnStatus.DENIED
            : ConversationManagerTurnStatus.INVALID;
    }

    private ConversationManagerTurnStatus mapExecutionStatus(
        AIExecutionStatus status
    ) {
        return switch (status) {
            case INVALID -> ConversationManagerTurnStatus.INVALID;
            case DENIED -> ConversationManagerTurnStatus.DENIED;
            case DEADLINE_EXCEEDED ->
                ConversationManagerTurnStatus.DEADLINE_EXCEEDED;
            case CANCELLED -> ConversationManagerTurnStatus.CANCELLED;
            default -> ConversationManagerTurnStatus.FAILED;
        };
    }

    private Instant effectiveDeadline(
        Instant requested,
        Instant startedAt,
        Duration maximumDuration
    ) {
        Instant maximum = startedAt.plus(maximumDuration);
        return requested != null && requested.isBefore(maximum)
            ? requested
            : maximum;
    }

    private String validateExternalMessage(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                "external message is required"
            );
        }
        String normalized = value.trim();
        if (normalized.length()
            > ConversationManagerDirective.MAX_MESSAGE_CHARACTERS) {
            throw new IllegalArgumentException(
                "external message exceeds the manager limit"
            );
        }
        return normalized;
    }

    private String fingerprint(
        ConversationManagerTurnRequest<?> request,
        RegisteredConversationManager registered
    ) {
        LinkedHashMap<String, Object> value = new LinkedHashMap<>();
        value.put("managerId", registered.id().toString());
        value.put("managerContentHash", registered.contentHash());
        value.put(
            "inputType",
            registered.definition().inputType().getName()
        );
        value.put(
            "inputHash",
            canonicalJson.hashValue(request.input())
        );
        value.put(
            "deadline",
            request.deadline() == null
                ? "managerMaximum"
                : request.deadline().toString()
        );
        return canonicalJson.hashValue(value);
    }

    private String managerIdempotencyKey(
        ConversationManagerTurnRequest<?> request,
        RegisteredConversationManager registered
    ) {
        return "manager:" + CanonicalJsonSupport.sha256(
            registered.id()
                + "\n" + registered.contentHash()
                + "\n" + request.idempotencyKey()
        );
    }

    private String targetIdempotencyKey(
        ConversationManagerTurnRequest<?> request,
        AIExecutionResult<?> managerExecution,
        SpecialistId target
    ) {
        return "manager-target:" + CanonicalJsonSupport.sha256(
            managerExecution.invocationId()
                + "\n" + target
                + "\n" + request.idempotencyKey()
        );
    }

    @SuppressWarnings("unchecked")
    private <I> ConversationManagerDefinition<I> typedDefinition(
        RegisteredConversationManager registered,
        I input
    ) {
        ConversationManagerDefinition<?> definition =
            registered.definition();
        if (!definition.inputType().isInstance(input)) {
            throw new IllegalArgumentException(
                "Manager input type does not match"
            );
        }
        return (ConversationManagerDefinition<I>) definition;
    }

    private void cleanup() {
        Instant now = clock.instant();
        synchronized (replays) {
            replays.entrySet().removeIf(entry ->
                entry.getValue().result != null
                    && !entry.getValue().expiresAt.isAfter(now)
            );
        }
    }

    private ConversationManagerTurnResult cancelled(
        ConversationManagerId managerId,
        Instant startedAt,
        ApprovedConversationSnapshot snapshot,
        String managerInvocationId
    ) {
        return failure(
            managerId,
            ConversationManagerTurnStatus.CANCELLED,
            "MANAGER_TURN_CANCELLED",
            "The conversation-manager turn was cancelled.",
            true,
            startedAt,
            snapshot,
            managerInvocationId
        );
    }

    private ConversationManagerTurnResult failure(
        ConversationManagerId managerId,
        ConversationManagerTurnStatus status,
        String reason,
        String publicMessage,
        boolean retryable,
        Instant startedAt
    ) {
        return new ConversationManagerTurnResult(
            managerTurnId(),
            managerId,
            status,
            null,
            null,
            null,
            null,
            null,
            0,
            new ConversationManagerFailure(
                reason,
                publicMessage,
                retryable
            ),
            false,
            startedAt,
            clock.instant()
        );
    }

    private ConversationManagerTurnResult failure(
        ConversationManagerId managerId,
        ConversationManagerTurnStatus status,
        String reason,
        String publicMessage,
        boolean retryable,
        Instant startedAt,
        ApprovedConversationSnapshot snapshot
    ) {
        return failure(
            managerId,
            status,
            reason,
            publicMessage,
            retryable,
            startedAt,
            snapshot,
            null
        );
    }

    private ConversationManagerTurnResult failure(
        ConversationManagerId managerId,
        ConversationManagerTurnStatus status,
        String reason,
        String publicMessage,
        boolean retryable,
        Instant startedAt,
        ApprovedConversationSnapshot snapshot,
        String managerInvocationId
    ) {
        return new ConversationManagerTurnResult(
            managerTurnId(),
            managerId,
            status,
            null,
            null,
            managerInvocationId,
            null,
            snapshot == null ? null : snapshot.revision(),
            snapshot == null ? 0 : snapshot.sourceTurnCount(),
            new ConversationManagerFailure(
                reason,
                publicMessage,
                retryable
            ),
            false,
            startedAt,
            clock.instant()
        );
    }

    private String managerTurnId() {
        return "manager-turn-" + UUID.randomUUID();
    }

    private record ReplayBinding(
        ExecutionAccessBinding access,
        String userId,
        String conversationId,
        ConversationManagerId managerId,
        String managerContentHash,
        String idempotencyKey
    ) {}

    private static final class ReplayEntry {

        private final String fingerprint;
        private volatile Instant expiresAt;
        private volatile ConversationManagerTurnResult result;

        private ReplayEntry(String fingerprint, Instant expiresAt) {
            this.fingerprint = Objects.requireNonNull(
                fingerprint,
                "fingerprint is required"
            );
            this.expiresAt = Objects.requireNonNull(
                expiresAt,
                "expiresAt is required"
            );
        }
    }
}
