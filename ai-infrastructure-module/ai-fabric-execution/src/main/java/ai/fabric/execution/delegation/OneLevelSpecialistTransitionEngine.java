package ai.fabric.execution.delegation;

import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared mechanics for relation-specific one-level specialist transitions.
 */
final class OneLevelSpecialistTransitionEngine {

    static final int MAX_DEPTH = 1;
    static final String DIAGNOSTIC_DEADLINE = "executionDeadline";
    static final String DELEGATION_DEPTH = "delegationDepth";
    static final String HANDOFF_DEPTH = "handoffDepth";

    private static final Logger log = LoggerFactory.getLogger(
        OneLevelSpecialistTransitionEngine.class
    );

    private final SpecialistRegistry specialistRegistry;
    private final SpecialistClientFactory clientFactory;
    private final CanonicalJsonSupport canonicalJson;
    private final Clock clock;
    private final Duration resultTtl;
    private final Relation relation;
    private final Map<IdempotencyBinding, ReplayEntry> replays =
        new ConcurrentHashMap<>();

    OneLevelSpecialistTransitionEngine(
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        Duration resultTtl,
        Relation relation
    ) {
        this.specialistRegistry = Objects.requireNonNull(
            specialistRegistry,
            "specialistRegistry is required"
        );
        this.clientFactory = Objects.requireNonNull(
            clientFactory,
            "clientFactory is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.resultTtl = requirePositive(resultTtl, "resultTtl");
        this.relation = Objects.requireNonNull(
            relation,
            "relation is required"
        );
    }

    <P, I, O> TransitionResult<P, O> transition(
        TransitionRequest<P, I> request,
        Class<I> targetInputType,
        Class<O> targetOutputType
    ) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(targetInputType, "targetInputType is required");
        Objects.requireNonNull(targetOutputType, "targetOutputType is required");
        cleanup();

        String fingerprint;
        try {
            fingerprint = requestFingerprint(
                request,
                targetInputType,
                targetOutputType
            );
        } catch (RuntimeException ex) {
            return rejected(
                request,
                AIExecutionStatus.INVALID,
                relation.reason("FINGERPRINT_INVALID"),
                "The " + relation.label()
                    + " request could not be snapshotted safely.",
                false
            );
        }
        IdempotencyBinding binding = new IdempotencyBinding(
            AccessBinding.from(request.trustedExecutionContext()),
            request.idempotencyKey()
        );
        ReplayEntry candidate = new ReplayEntry(
            fingerprint,
            clock.instant().plus(resultTtl)
        );
        ReplayEntry entry = replays.putIfAbsent(binding, candidate);
        if (entry == null) {
            entry = candidate;
        }
        synchronized (entry) {
            if (!entry.fingerprint.equals(fingerprint)) {
                return rejected(
                    request,
                    AIExecutionStatus.INVALID,
                    relation.reason("IDEMPOTENCY_CONFLICT"),
                    "The " + relation.label()
                        + " idempotency key was already used for different work.",
                    false
                );
            }
            if (entry.result != null) {
                return replay(entry.result);
            }
            TransitionResult<P, O> result = execute(
                request,
                targetInputType,
                targetOutputType
            );
            entry.result = result;
            entry.expiresAt = clock.instant().plus(resultTtl);
            return result;
        }
    }

    private <P, I, O> TransitionResult<P, O> execute(
        TransitionRequest<P, I> request,
        Class<I> targetInputType,
        Class<O> targetOutputType
    ) {
        Instant startedAt = clock.instant();
        AIExecutionResult<P> source = request.sourceExecution();
        SpecialistId sourceId = source.specialistId();
        SpecialistId targetId = request.targetSpecialistId();

        if (!source.succeeded() || source.output() == null) {
            return rejected(
                request,
                AIExecutionStatus.INVALID,
                relation.reason("SOURCE_NOT_SUCCESSFUL"),
                "Only a successful validated specialist result may "
                    + relation.verb() + ".",
                false,
                startedAt
            );
        }
        if (relationshipDepth(source) != 0) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                relation.reason("DEPTH_EXCEEDED"),
                "A delegated child or handoff successor cannot start another transition.",
                false,
                startedAt
            );
        }

        RegisteredSpecialist sourceRegistration =
            specialistRegistry.findRegistered(sourceId).orElse(null);
        if (sourceRegistration == null) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                relation.reason("SOURCE_NOT_REGISTERED"),
                "The " + relation.sourceLabel()
                    + " is not currently registered.",
                false,
                startedAt
            );
        }
        Object sourceHash = source.diagnostics().get(
            "specialistContentHash"
        );
        if (!(sourceHash instanceof String hash)
            || !sourceRegistration.contentHash().equals(hash)) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                relation.reason("SOURCE_CHANGED"),
                "The " + relation.sourceLabel()
                    + " changed before the target invocation.",
                false,
                startedAt
            );
        }
        if (!relation.allows(sourceRegistration.definition(), targetId)) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                relation.reason("TARGET_NOT_ALLOWED"),
                "The requested specialist is not an approved "
                    + relation.targetLabel() + ".",
                false,
                startedAt
            );
        }
        RegisteredSpecialist targetRegistration =
            specialistRegistry.findRegistered(targetId).orElse(null);
        if (targetRegistration == null) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                relation.reason("TARGET_NOT_REGISTERED"),
                "The requested " + relation.targetLabel()
                    + " is not registered.",
                false,
                startedAt
            );
        }
        if (!targetRegistration.definition().executionProfile()
            .requestedCapabilities().proposableWriteActions().isEmpty()) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                relation.reason("WRITE_TARGET_UNSUPPORTED"),
                "One-level " + relation.label()
                    + " supports read-only targets.",
                false,
                startedAt
            );
        }

        Instant deadline;
        try {
            deadline = effectiveDeadline(
                source,
                sourceRegistration.definition(),
                request.deadline()
            );
        } catch (IllegalArgumentException ex) {
            return rejected(
                request,
                AIExecutionStatus.INVALID,
                relation.sourceDeadlineInvalidReason(),
                "The " + relation.sourceLabel()
                    + " execution deadline is invalid.",
                false,
                startedAt
            );
        }
        if (!clock.instant().isBefore(deadline)) {
            return rejected(
                request,
                AIExecutionStatus.DEADLINE_EXCEEDED,
                relation.reason("DEADLINE_EXCEEDED"),
                "The " + relation.sourceLabel()
                    + " execution deadline has elapsed.",
                true,
                startedAt
            );
        }

        SpecialistClient<I, O> targetClient;
        try {
            targetClient = clientFactory.bind(
                targetId,
                targetInputType,
                targetOutputType
            );
        } catch (RuntimeException ex) {
            return rejected(
                request,
                AIExecutionStatus.INVALID,
                relation.reason("TARGET_BINDING_INVALID"),
                "The " + relation.targetLabel()
                    + " does not satisfy the requested typed contract.",
                false,
                startedAt
            );
        }

        AIExecutionResult<O> target;
        try {
            target = targetClient.execute(new SpecialistInvocation<>(
                request.targetInput(),
                request.trustedExecutionContext(),
                null,
                deadline,
                targetIdempotencyKey(request, targetId)
            ));
        } catch (RuntimeException ex) {
            log.warn(
                "Specialist {} sourceInvocation={} source={} target={} failed before a result: {}",
                relation.label(),
                source.invocationId(),
                sourceId,
                targetId,
                ex.getClass().getSimpleName()
            );
            return rejected(
                request,
                AIExecutionStatus.FAILED,
                relation.reason("TARGET_INVOCATION_FAILED"),
                "The " + relation.targetLabel()
                    + " could not be invoked safely.",
                false,
                startedAt
            );
        }

        if (target.waitingForInput()) {
            try {
                targetClient.cancel(
                    target.invocationId(),
                    request.trustedExecutionContext()
                );
            } catch (RuntimeException ex) {
                log.warn(
                    "Specialist {} sourceInvocation={} source={} target={} could not cancel unsupported input wait: {}",
                    relation.label(),
                    source.invocationId(),
                    sourceId,
                    targetId,
                    ex.getClass().getSimpleName()
                );
                return rejected(
                    request,
                    AIExecutionStatus.FAILED,
                    relation.waitCancellationFailureReason(),
                    "The unsupported " + relation.label()
                        + " input wait could not be cancelled safely.",
                    false,
                    startedAt
                );
            }
            return rejected(
                request,
                AIExecutionStatus.INVALID,
                relation.waitUnsupportedReason(),
                "One-level " + relation.label()
                    + " does not support a target input wait.",
                false,
                startedAt
            );
        }
        if (target.status() == AIExecutionStatus.CONFIRMATION_REQUIRED) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                relation.writeProposalUnsupportedReason(),
                "One-level " + relation.label()
                    + " does not support target write proposals.",
                false,
                startedAt
            );
        }

        String transitionId = transitionId();
        AIExecutionResult<O> lineaged = withLineage(
            target,
            transitionId,
            source
        );
        TransitionFailure failure = lineaged.failure() == null
            ? null
            : new TransitionFailure(
                lineaged.failure().reason(),
                lineaged.failure().publicMessage(),
                lineaged.failure().retryable()
            );
        TransitionResult<P, O> result = new TransitionResult<>(
            transitionId,
            source.invocationId(),
            sourceId,
            targetId,
            lineaged.status(),
            source.output(),
            lineaged,
            failure,
            false,
            startedAt,
            clock.instant()
        );
        log.info(
            "Specialist {} {} sourceInvocation={} source={} target={} status={}",
            relation.label(),
            transitionId,
            source.invocationId(),
            sourceId,
            targetId,
            result.status()
        );
        return result;
    }

    private <P, I> String requestFingerprint(
        TransitionRequest<P, I> request,
        Class<I> inputType,
        Class<?> outputType
    ) {
        return canonicalJson.hashValue(Map.of(
            "relationship",
            relation.name(),
            "sourceInvocationId",
            request.sourceExecution().invocationId(),
            "sourceSpecialist",
            request.sourceExecution().specialistId().toString(),
            "sourceOutputHash",
            request.sourceExecution().output() == null
                ? "none"
                : canonicalJson.hashValue(request.sourceExecution().output()),
            "targetSpecialist",
            request.targetSpecialistId().toString(),
            "targetInputHash",
            canonicalJson.hashValue(request.targetInput()),
            "targetInputType",
            inputType.getName(),
            "targetOutputType",
            outputType.getName(),
            "deadline",
            request.deadline() != null
                ? request.deadline().toString()
                : "source"
        ));
    }

    private <P, I> String targetIdempotencyKey(
        TransitionRequest<P, I> request,
        SpecialistId targetId
    ) {
        return relation.label() + ":" + CanonicalJsonSupport.sha256(
            request.sourceExecution().invocationId()
                + "\n" + targetId
                + "\n" + request.idempotencyKey()
        );
    }

    private Instant effectiveDeadline(
        AIExecutionResult<?> source,
        SpecialistDefinition<?, ?> sourceDefinition,
        Instant requested
    ) {
        Instant sourceDeadline = sourceDeadline(source, sourceDefinition);
        return requested != null && requested.isBefore(sourceDeadline)
            ? requested
            : sourceDeadline;
    }

    private Instant sourceDeadline(
        AIExecutionResult<?> source,
        SpecialistDefinition<?, ?> sourceDefinition
    ) {
        Object value = source.diagnostics().get(DIAGNOSTIC_DEADLINE);
        if (value != null) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException(
                    "Source execution deadline must be an ISO-8601 instant"
                );
            }
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                    "Source execution deadline must be an ISO-8601 instant",
                    ex
                );
            }
        }
        return source.startedAt().plus(
            sourceDefinition.limits().maxDuration()
        );
    }

    private int relationshipDepth(AIExecutionResult<?> source) {
        return Math.max(
            depth(source.diagnostics().get(DELEGATION_DEPTH)),
            depth(source.diagnostics().get(HANDOFF_DEPTH))
        );
    }

    private int depth(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private <O> AIExecutionResult<O> withLineage(
        AIExecutionResult<O> target,
        String transitionId,
        AIExecutionResult<?> source
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(
            target.diagnostics()
        );
        diagnostics.put(relation.label(), true);
        diagnostics.put(relation.idDiagnostic(), transitionId);
        diagnostics.put(relation.depthDiagnostic(), MAX_DEPTH);
        diagnostics.put(
            relation.sourceInvocationDiagnostic(),
            source.invocationId()
        );
        diagnostics.put(
            relation.sourceSpecialistDiagnostic(),
            source.specialistId().toString()
        );
        return new AIExecutionResult<>(
            target.invocationId(),
            target.specialistId(),
            target.status(),
            target.output(),
            target.evidence(),
            Map.copyOf(diagnostics),
            target.failure(),
            target.startedAt(),
            target.completedAt(),
            target.actionProposal(),
            target.needsUserInput()
        );
    }

    private <P, I, O> TransitionResult<P, O> rejected(
        TransitionRequest<P, I> request,
        AIExecutionStatus status,
        String reason,
        String message,
        boolean retryable
    ) {
        return rejected(
            request,
            status,
            reason,
            message,
            retryable,
            clock.instant()
        );
    }

    private <P, I, O> TransitionResult<P, O> rejected(
        TransitionRequest<P, I> request,
        AIExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        Instant startedAt
    ) {
        AIExecutionResult<P> source = request.sourceExecution();
        log.info(
            "Specialist {} sourceInvocation={} source={} target={} status={} reason={}",
            relation.label(),
            source.invocationId(),
            source.specialistId(),
            request.targetSpecialistId(),
            status,
            reason
        );
        return new TransitionResult<>(
            transitionId(),
            source.invocationId(),
            source.specialistId(),
            request.targetSpecialistId(),
            status,
            source.output(),
            null,
            new TransitionFailure(reason, message, retryable),
            false,
            startedAt,
            clock.instant()
        );
    }

    @SuppressWarnings("unchecked")
    private <P, O> TransitionResult<P, O> replay(
        TransitionResult<?, ?> result
    ) {
        TransitionResult<P, O> typed = (TransitionResult<P, O>) result;
        return typed.replayed()
            ? typed
            : new TransitionResult<>(
                typed.transitionId(),
                typed.sourceInvocationId(),
                typed.sourceSpecialistId(),
                typed.targetSpecialistId(),
                typed.status(),
                typed.sourceOutput(),
                typed.targetExecution(),
                typed.failure(),
                true,
                typed.startedAt(),
                typed.completedAt()
            );
    }

    private void cleanup() {
        Instant now = clock.instant();
        replays.entrySet().removeIf(entry -> {
            ReplayEntry value = entry.getValue();
            synchronized (value) {
                return value.result != null && now.isAfter(value.expiresAt);
            }
        });
    }

    private String transitionId() {
        return relation.idPrefix() + UUID.randomUUID();
    }

    private static Duration requirePositive(
        Duration value,
        String field
    ) {
        Objects.requireNonNull(value, field + " is required");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(field + " must be positive");
        }
        return value;
    }

    enum Relation {
        DELEGATION,
        HANDOFF;

        boolean allows(
            SpecialistDefinition<?, ?> source,
            SpecialistId target
        ) {
            return this == DELEGATION
                ? source.delegationPolicy().allows(target)
                : source.handoffPolicy().allows(target);
        }

        String label() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }

        String verb() {
            return this == DELEGATION ? "delegate" : "hand off";
        }

        String sourceLabel() {
            return this == DELEGATION ? "delegation source" : "predecessor";
        }

        String targetLabel() {
            return this == DELEGATION
                ? "delegation target"
                : "handoff successor";
        }

        String reason(String suffix) {
            return name() + "_" + suffix;
        }

        String waitUnsupportedReason() {
            return this == DELEGATION
                ? "DELEGATED_INPUT_WAIT_UNSUPPORTED"
                : "HANDOFF_INPUT_WAIT_UNSUPPORTED";
        }

        String waitCancellationFailureReason() {
            return this == DELEGATION
                ? "DELEGATED_INPUT_WAIT_CANCELLATION_FAILED"
                : "HANDOFF_INPUT_WAIT_CANCELLATION_FAILED";
        }

        String writeProposalUnsupportedReason() {
            return this == DELEGATION
                ? "DELEGATED_WRITE_PROPOSAL_UNSUPPORTED"
                : "HANDOFF_WRITE_PROPOSAL_UNSUPPORTED";
        }

        String sourceDeadlineInvalidReason() {
            return this == DELEGATION
                ? "DELEGATION_PARENT_DEADLINE_INVALID"
                : "HANDOFF_PREDECESSOR_DEADLINE_INVALID";
        }

        String idPrefix() {
            return this == DELEGATION ? "dlg-" : "hnd-";
        }

        String idDiagnostic() {
            return this == DELEGATION ? "delegationId" : "handoffId";
        }

        String depthDiagnostic() {
            return this == DELEGATION ? DELEGATION_DEPTH : HANDOFF_DEPTH;
        }

        String sourceInvocationDiagnostic() {
            return this == DELEGATION
                ? "parentInvocationId"
                : "predecessorInvocationId";
        }

        String sourceSpecialistDiagnostic() {
            return this == DELEGATION
                ? "sourceSpecialist"
                : "predecessorSpecialist";
        }
    }

    record TransitionRequest<P, I>(
        AIExecutionResult<P> sourceExecution,
        SpecialistId targetSpecialistId,
        I targetInput,
        TrustedExecutionContext trustedExecutionContext,
        Instant deadline,
        String idempotencyKey
    ) {}

    record TransitionFailure(
        String reason,
        String publicMessage,
        boolean retryable
    ) {}

    record TransitionResult<P, O>(
        String transitionId,
        String sourceInvocationId,
        SpecialistId sourceSpecialistId,
        SpecialistId targetSpecialistId,
        AIExecutionStatus status,
        P sourceOutput,
        AIExecutionResult<O> targetExecution,
        TransitionFailure failure,
        boolean replayed,
        Instant startedAt,
        Instant completedAt
    ) {}

    private record AccessBinding(
        String principalId,
        ai.fabric.execution.context.ExecutionPrincipalType principalType,
        String subjectType,
        String subjectId,
        ai.fabric.execution.context.ExecutionSource source,
        String tenantId,
        String deploymentId
    ) {
        private static AccessBinding from(TrustedExecutionContext context) {
            ExecutionSubjectRef subject = context.subject();
            return new AccessBinding(
                context.initiator().principalId(),
                context.initiator().principalType(),
                subject != null ? subject.subjectType() : null,
                subject != null ? subject.subjectId() : null,
                context.source(),
                context.tenantId(),
                context.deploymentId()
            );
        }
    }

    private record IdempotencyBinding(
        AccessBinding access,
        String idempotencyKey
    ) {}

    private static final class ReplayEntry {
        private final String fingerprint;
        private Instant expiresAt;
        private TransitionResult<?, ?> result;

        private ReplayEntry(String fingerprint, Instant expiresAt) {
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }
    }
}
