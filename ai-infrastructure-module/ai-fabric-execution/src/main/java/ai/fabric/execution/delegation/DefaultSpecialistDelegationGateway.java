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
 * Enforces exact-version, one-level, read-only specialist delegation.
 */
public final class DefaultSpecialistDelegationGateway
    implements SpecialistDelegationGateway {

    public static final int MAX_DEPTH = 1;
    public static final String DIAGNOSTIC_DEPTH = "delegationDepth";
    public static final String DIAGNOSTIC_DEADLINE = "executionDeadline";

    private static final Logger log = LoggerFactory.getLogger(
        DefaultSpecialistDelegationGateway.class
    );

    private final SpecialistRegistry specialistRegistry;
    private final SpecialistClientFactory clientFactory;
    private final CanonicalJsonSupport canonicalJson;
    private final Clock clock;
    private final Duration resultTtl;
    private final Map<IdempotencyBinding, ReplayEntry> replays =
        new ConcurrentHashMap<>();

    public DefaultSpecialistDelegationGateway(
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        Duration resultTtl
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
    }

    @Override
    public <P, I, O> SpecialistDelegationResult<P, O> delegate(
        SpecialistDelegationRequest<P, I> request,
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
                "DELEGATION_FINGERPRINT_INVALID",
                "The delegation request could not be snapshotted safely.",
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
                    "DELEGATION_IDEMPOTENCY_CONFLICT",
                    "The delegation idempotency key was already used for different work.",
                    false
                );
            }
            if (entry.result != null) {
                return replay(entry.result);
            }
            SpecialistDelegationResult<P, O> result = execute(
                request,
                targetInputType,
                targetOutputType
            );
            entry.result = result;
            entry.expiresAt = clock.instant().plus(resultTtl);
            return result;
        }
    }

    private <P, I, O> SpecialistDelegationResult<P, O> execute(
        SpecialistDelegationRequest<P, I> request,
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
                "DELEGATION_SOURCE_NOT_SUCCESSFUL",
                "Only a successful validated specialist result may delegate.",
                false,
                startedAt
            );
        }
        int parentDepth = delegationDepth(source);
        if (parentDepth != 0) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                "DELEGATION_DEPTH_EXCEEDED",
                "Delegated specialists cannot delegate again.",
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
                "DELEGATION_SOURCE_NOT_REGISTERED",
                "The delegation source is not currently registered.",
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
                "DELEGATION_SOURCE_CHANGED",
                "The delegation source changed before the child invocation.",
                false,
                startedAt
            );
        }
        if (!sourceRegistration.definition().delegationPolicy()
            .allows(targetId)) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                "DELEGATION_TARGET_NOT_ALLOWED",
                "The requested specialist is not an approved delegation target.",
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
                "DELEGATION_TARGET_NOT_REGISTERED",
                "The requested delegation target is not registered.",
                false,
                startedAt
            );
        }
        if (!targetRegistration.definition().executionProfile()
            .requestedCapabilities().proposableWriteActions().isEmpty()) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                "DELEGATION_WRITE_TARGET_UNSUPPORTED",
                "One-level delegation supports read-only targets.",
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
                "DELEGATION_PARENT_DEADLINE_INVALID",
                "The parent execution deadline is invalid.",
                false,
                startedAt
            );
        }
        if (!clock.instant().isBefore(deadline)) {
            return rejected(
                request,
                AIExecutionStatus.DEADLINE_EXCEEDED,
                "DELEGATION_DEADLINE_EXCEEDED",
                "The parent execution deadline has elapsed.",
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
                "DELEGATION_TARGET_BINDING_INVALID",
                "The delegation target does not satisfy the requested typed contract.",
                false,
                startedAt
            );
        }

        AIExecutionResult<O> child;
        try {
            child = targetClient.execute(new SpecialistInvocation<>(
                request.targetInput(),
                request.trustedExecutionContext(),
                null,
                deadline,
                childIdempotencyKey(request, targetId)
            ));
        } catch (RuntimeException ex) {
            log.warn(
                "Specialist delegation parent={} source={} target={} failed before a result: {}",
                source.invocationId(),
                sourceId,
                targetId,
                ex.getClass().getSimpleName()
            );
            return rejected(
                request,
                AIExecutionStatus.FAILED,
                "DELEGATION_TARGET_INVOCATION_FAILED",
                "The delegated specialist could not be invoked safely.",
                false,
                startedAt
            );
        }

        if (child.waitingForInput()) {
            try {
                targetClient.cancel(
                    child.invocationId(),
                    request.trustedExecutionContext()
                );
            } catch (RuntimeException ex) {
                log.warn(
                    "Specialist delegation parent={} source={} target={} could not cancel unsupported input wait: {}",
                    source.invocationId(),
                    sourceId,
                    targetId,
                    ex.getClass().getSimpleName()
                );
                return rejected(
                    request,
                    AIExecutionStatus.FAILED,
                    "DELEGATED_INPUT_WAIT_CANCELLATION_FAILED",
                    "The unsupported delegated input wait could not be cancelled safely.",
                    false,
                    startedAt
                );
            }
            return rejected(
                request,
                AIExecutionStatus.INVALID,
                "DELEGATED_INPUT_WAIT_UNSUPPORTED",
                "One-level delegation does not support a child input wait.",
                false,
                startedAt
            );
        }
        if (child.status() == AIExecutionStatus.CONFIRMATION_REQUIRED) {
            return rejected(
                request,
                AIExecutionStatus.DENIED,
                "DELEGATED_WRITE_PROPOSAL_UNSUPPORTED",
                "One-level delegation does not support child write proposals.",
                false,
                startedAt
            );
        }

        String delegationId = delegationId();
        AIExecutionResult<O> lineaged = withLineage(
            child,
            delegationId,
            source
        );
        SpecialistDelegationFailure failure = lineaged.failure() == null
            ? null
            : new SpecialistDelegationFailure(
                lineaged.failure().reason(),
                lineaged.failure().publicMessage(),
                lineaged.failure().retryable()
            );
        SpecialistDelegationResult<P, O> result =
            new SpecialistDelegationResult<>(
                delegationId,
                source.invocationId(),
                sourceId,
                targetId,
                MAX_DEPTH,
                lineaged.status(),
                source.output(),
                lineaged,
                failure,
                false,
                startedAt,
                clock.instant()
            );
        log.info(
            "Specialist delegation {} parent={} source={} target={} status={}",
            delegationId,
            source.invocationId(),
            sourceId,
            targetId,
            result.status()
        );
        return result;
    }

    private <P, I> String requestFingerprint(
        SpecialistDelegationRequest<P, I> request,
        Class<I> inputType,
        Class<?> outputType
    ) {
        return canonicalJson.hashValue(Map.of(
            "parentInvocationId",
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
                : "parent"
        ));
    }

    private <P, I> String childIdempotencyKey(
        SpecialistDelegationRequest<P, I> request,
        SpecialistId targetId
    ) {
        return "delegation:" + CanonicalJsonSupport.sha256(
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
        Instant parent = parentDeadline(source, sourceDefinition);
        return requested != null && requested.isBefore(parent)
            ? requested
            : parent;
    }

    private Instant parentDeadline(
        AIExecutionResult<?> source,
        SpecialistDefinition<?, ?> sourceDefinition
    ) {
        Object value = source.diagnostics().get(DIAGNOSTIC_DEADLINE);
        if (value != null) {
            if (!(value instanceof String text)) {
                throw new IllegalArgumentException(
                    "Parent execution deadline must be an ISO-8601 instant"
                );
            }
            try {
                return Instant.parse(text);
            } catch (DateTimeParseException ex) {
                throw new IllegalArgumentException(
                    "Parent execution deadline must be an ISO-8601 instant",
                    ex
                );
            }
        }
        return source.startedAt().plus(
            sourceDefinition.limits().maxDuration()
        );
    }

    private int delegationDepth(AIExecutionResult<?> source) {
        Object value = source.diagnostics().get(DIAGNOSTIC_DEPTH);
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
        AIExecutionResult<O> child,
        String delegationId,
        AIExecutionResult<?> source
    ) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(
            child.diagnostics()
        );
        diagnostics.put("delegation", true);
        diagnostics.put("delegationId", delegationId);
        diagnostics.put(DIAGNOSTIC_DEPTH, MAX_DEPTH);
        diagnostics.put("parentInvocationId", source.invocationId());
        diagnostics.put(
            "sourceSpecialist",
            source.specialistId().toString()
        );
        return new AIExecutionResult<>(
            child.invocationId(),
            child.specialistId(),
            child.status(),
            child.output(),
            child.evidence(),
            Map.copyOf(diagnostics),
            child.failure(),
            child.startedAt(),
            child.completedAt(),
            child.actionProposal(),
            child.needsUserInput()
        );
    }

    private <P, I, O> SpecialistDelegationResult<P, O> rejected(
        SpecialistDelegationRequest<P, I> request,
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

    private <P, I, O> SpecialistDelegationResult<P, O> rejected(
        SpecialistDelegationRequest<P, I> request,
        AIExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        Instant startedAt
    ) {
        AIExecutionResult<P> source = request.sourceExecution();
        log.info(
            "Specialist delegation parent={} source={} target={} status={} reason={}",
            source.invocationId(),
            source.specialistId(),
            request.targetSpecialistId(),
            status,
            reason
        );
        return new SpecialistDelegationResult<>(
            delegationId(),
            source.invocationId(),
            source.specialistId(),
            request.targetSpecialistId(),
            MAX_DEPTH,
            status,
            source.output(),
            null,
            new SpecialistDelegationFailure(reason, message, retryable),
            false,
            startedAt,
            clock.instant()
        );
    }

    @SuppressWarnings("unchecked")
    private <P, O> SpecialistDelegationResult<P, O> replay(
        SpecialistDelegationResult<?, ?> result
    ) {
        return ((SpecialistDelegationResult<P, O>) result).asReplayed();
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

    private String delegationId() {
        return "dlg-" + UUID.randomUUID();
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
        private SpecialistDelegationResult<?, ?> result;

        private ReplayEntry(String fingerprint, Instant expiresAt) {
            this.fingerprint = fingerprint;
            this.expiresAt = expiresAt;
        }
    }
}
