package ai.fabric.execution.gateway;

import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.LockSupport;

/**
 * Assigns one eligible dialogue owner to one frozen synchronous turn.
 */
public final class DefaultAIInteractiveExecutionGateway
    implements AIInteractiveExecutionGateway {

    private final AIExecutionGateway executionGateway;
    private final SharedInteractiveTurnCoordinator turnCoordinator;
    private final Clock clock;
    private final Duration waitPadding;
    private final long pollNanos;

    public DefaultAIInteractiveExecutionGateway(
        AIExecutionGateway executionGateway,
        SpecialistRegistry specialistRegistry,
        AIExecutionConversationSnapshotProvider snapshotProvider,
        AIExecutionConversationSnapshotRegistry snapshotRegistry,
        CanonicalJsonSupport canonicalJson,
        Clock clock
    ) {
        this(
            executionGateway,
            new SharedInteractiveTurnCoordinator(
                specialistRegistry,
                snapshotProvider,
                snapshotRegistry,
                canonicalJson
            ),
            clock,
            Duration.ofSeconds(5),
            Duration.ofMillis(10)
        );
    }

    public DefaultAIInteractiveExecutionGateway(
        AIExecutionGateway executionGateway,
        SharedInteractiveTurnCoordinator turnCoordinator,
        Clock clock
    ) {
        this(
            executionGateway,
            turnCoordinator,
            clock,
            Duration.ofSeconds(5),
            Duration.ofMillis(10)
        );
    }

    DefaultAIInteractiveExecutionGateway(
        AIExecutionGateway executionGateway,
        SpecialistRegistry specialistRegistry,
        AIExecutionConversationSnapshotProvider snapshotProvider,
        AIExecutionConversationSnapshotRegistry snapshotRegistry,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        Duration waitPadding,
        Duration pollInterval
    ) {
        this(
            executionGateway,
            new SharedInteractiveTurnCoordinator(
                specialistRegistry,
                snapshotProvider,
                snapshotRegistry,
                canonicalJson
            ),
            clock,
            waitPadding,
            pollInterval
        );
    }

    DefaultAIInteractiveExecutionGateway(
        AIExecutionGateway executionGateway,
        SharedInteractiveTurnCoordinator turnCoordinator,
        Clock clock,
        Duration waitPadding,
        Duration pollInterval
    ) {
        this.executionGateway = Objects.requireNonNull(
            executionGateway,
            "executionGateway is required"
        );
        this.turnCoordinator = Objects.requireNonNull(
            turnCoordinator,
            "turnCoordinator is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
        if (waitPadding == null || waitPadding.isNegative()) {
            throw new IllegalArgumentException(
                "waitPadding cannot be negative"
            );
        }
        if (pollInterval == null
            || pollInterval.isZero()
            || pollInterval.isNegative()) {
            throw new IllegalArgumentException(
                "pollInterval must be positive"
            );
        }
        this.waitPadding = waitPadding;
        this.pollNanos = pollInterval.toNanos();
    }

    @Override
    public <I, O> AIExecutionResult<O> execute(
        AIExecutionRequest<I> request
    ) {
        Objects.requireNonNull(request, "request is required");
        Instant startedAt = clock.instant();
        SpecialistId specialistId = request.specialistId();
        SharedInteractiveTurnCoordinator.CoordinatedTurn<
            AIExecutionResult<O>
        > coordinated = turnCoordinator.coordinate(
            specialistId,
            request.trustedExecutionContext(),
            request.conversationBinding(),
            request.idempotencyKey(),
            SharedInteractiveTurnCoordinator.RecordingPolicy.DIRECT,
            turn -> submitAndAwait(
                new AIExecutionRequest<>(
                    specialistId,
                    request.input(),
                    request.trustedExecutionContext(),
                    turn.approvedBinding(),
                    request.deadline(),
                    request.idempotencyKey()
                ),
                turn.definition(),
                startedAt
            )
        );
        if (!coordinated.succeeded()) {
            return failure(
                specialistId,
                coordinated.failure(),
                startedAt,
                coordinated.activeTurn()
            );
        }
        return coordinated.value();
    }

    private <I, O> AIExecutionResult<O> submitAndAwait(
        AIExecutionRequest<I> request,
        SpecialistDefinition<?, ?> definition,
        Instant startedAt
    ) {
        ExecutionHandle handle;
        try {
            handle = executionGateway.submit(request);
        } catch (RuntimeException ex) {
            return failure(
                "interactive-" + java.util.UUID.randomUUID(),
                request.specialistId(),
                AIExecutionStatus.FAILED,
                new AIExecutionFailure(
                    "INTERACTIVE_SUBMISSION_FAILED",
                    "The interactive turn could not be submitted.",
                    true
                ),
                startedAt,
                false
            );
        }

        long waitDeadline = System.nanoTime()
            + waitDuration(request, definition).toNanos();
        while (true) {
            OptionalSnapshot<O> snapshot = snapshot(
                handle,
                request
            );
            if (snapshot.result() != null) {
                return snapshot.result();
            }
            if (snapshot.terminal()) {
                return terminalFailure(
                    snapshot.handle(),
                    request.specialistId(),
                    startedAt
                );
            }
            if (System.nanoTime() >= waitDeadline) {
                executionGateway.cancel(
                    handle.invocationId(),
                    request.trustedExecutionContext()
                );
                return failure(
                    handle.invocationId(),
                    request.specialistId(),
                    AIExecutionStatus.DEADLINE_EXCEEDED,
                    new AIExecutionFailure(
                        "INTERACTIVE_WAIT_TIMEOUT",
                        "The interactive turn exceeded its bounded wait.",
                        true
                    ),
                    startedAt,
                    false
                );
            }
            if (Thread.currentThread().isInterrupted()) {
                executionGateway.cancel(
                    handle.invocationId(),
                    request.trustedExecutionContext()
                );
                Thread.currentThread().interrupt();
                return failure(
                    handle.invocationId(),
                    request.specialistId(),
                    AIExecutionStatus.CANCELLED,
                    new AIExecutionFailure(
                        "INTERACTIVE_WAIT_INTERRUPTED",
                        "The interactive turn was interrupted.",
                        true
                    ),
                    startedAt,
                    false
                );
            }
            LockSupport.parkNanos(pollNanos);
        }
    }

    @SuppressWarnings("unchecked")
    private <O> OptionalSnapshot<O> snapshot(
        ExecutionHandle submitted,
        AIExecutionRequest<?> request
    ) {
        var snapshot = executionGateway.find(
            submitted.invocationId(),
            request.trustedExecutionContext()
        );
        if (snapshot.isPresent()) {
            ExecutionSnapshot value = snapshot.get();
            if (value.result() != null) {
                return new OptionalSnapshot<>(
                    (AIExecutionResult<O>) value.result(),
                    value.handle(),
                    true
                );
            }
            return new OptionalSnapshot<>(
                null,
                value.handle(),
                isTerminal(value.handle().status())
            );
        }
        return new OptionalSnapshot<>(
            null,
            submitted,
            isTerminal(submitted.status())
        );
    }

    private Duration waitDuration(
        AIExecutionRequest<?> request,
        SpecialistDefinition<?, ?> definition
    ) {
        Duration bounded = definition.limits().maxDuration()
            .plus(waitPadding);
        if (request.deadline() == null) {
            return bounded;
        }
        Duration requested = Duration.between(
            clock.instant(),
            request.deadline()
        );
        if (requested.isNegative() || requested.isZero()) {
            return Duration.ofNanos(1);
        }
        return requested.compareTo(bounded) < 0
            ? requested
            : bounded;
    }

    private boolean isTerminal(ExecutionHandleStatus status) {
        return status == ExecutionHandleStatus.SUCCEEDED
            || status == ExecutionHandleStatus.FAILED
            || status == ExecutionHandleStatus.CANCELLED
            || status == ExecutionHandleStatus.REJECTED
            || status == ExecutionHandleStatus.EXPIRED;
    }

    private <O> AIExecutionResult<O> terminalFailure(
        ExecutionHandle handle,
        SpecialistId specialistId,
        Instant startedAt
    ) {
        String reason = handle.failureReason() == null
            ? "INTERACTIVE_RESULT_UNAVAILABLE"
            : handle.failureReason();
        AIExecutionStatus status = switch (handle.status()) {
            case CANCELLED -> AIExecutionStatus.CANCELLED;
            case EXPIRED -> AIExecutionStatus.DEADLINE_EXCEEDED;
            case REJECTED -> "IDEMPOTENCY_CONFLICT".equals(reason)
                ? AIExecutionStatus.INVALID
                : AIExecutionStatus.FAILED;
            default -> AIExecutionStatus.FAILED;
        };
        boolean retryable = !"IDEMPOTENCY_CONFLICT".equals(reason);
        return failure(
            handle.invocationId(),
            specialistId,
            status,
            new AIExecutionFailure(
                reason,
                publicTerminalMessage(reason),
                retryable
            ),
            startedAt,
            false
        );
    }

    private String publicTerminalMessage(String reason) {
        if ("IDEMPOTENCY_CONFLICT".equals(reason)) {
            return "The idempotency key was already used for another request.";
        }
        return "The interactive turn ended without an execution result.";
    }

    private <O> AIExecutionResult<O> failure(
        SpecialistId specialistId,
        AIExecutionFailure failure,
        Instant startedAt,
        boolean activeTurn
    ) {
        return failure(
            "interactive-" + java.util.UUID.randomUUID(),
            specialistId,
            failure.reason().equals("CONVERSATION_BUSY")
                ? AIExecutionStatus.DENIED
                : AIExecutionStatus.INVALID,
            failure,
            startedAt,
            activeTurn
        );
    }

    private <O> AIExecutionResult<O> failure(
        String invocationId,
        SpecialistId specialistId,
        AIExecutionStatus status,
        AIExecutionFailure failure,
        Instant startedAt,
        boolean activeTurn
    ) {
        Map<String, Object> diagnostics = activeTurn
            ? Map.of("interactiveTurn", true)
            : Map.of();
        return new AIExecutionResult<>(
            invocationId,
            specialistId,
            status,
            null,
            List.of(),
            diagnostics,
            failure,
            startedAt,
            clock.instant()
        );
    }

    private record OptionalSnapshot<O>(
        AIExecutionResult<O> result,
        ExecutionHandle handle,
        boolean terminal
    ) {}
}
