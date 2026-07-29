package ai.fabric.execution.gateway;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.input.NeedsUserInput;
import ai.fabric.execution.input.SpecialistInputContinuation;
import ai.fabric.execution.input.SpecialistInputRequirement;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.manifest.SpecialistSchemaDefinition;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded process-local state for factual input waits.
 *
 * <p>Entries intentionally do not survive restart and never log retained input or responses.</p>
 */
final class EphemeralInputWaitStore {

    private final Clock clock;
    private final AIExecutionProperties.InputWaits properties;
    private final Map<String, Entry> byRequestId = new ConcurrentHashMap<>();
    private final Map<String, String> latestByInvocation =
        new ConcurrentHashMap<>();

    EphemeralInputWaitStore(
        Clock clock,
        AIExecutionProperties.InputWaits properties
    ) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.properties = Objects.requireNonNull(
            properties,
            "properties are required"
        );
        if (properties.getDefaultTtl().compareTo(
                properties.getMaxTtl()
            ) > 0) {
            throw new IllegalArgumentException(
                "input-waits.default-ttl must not exceed max-ttl"
            );
        }
    }

    boolean enabled() {
        return properties.isEnabled();
    }

    synchronized Entry create(
        String invocationId,
        AIExecutionRequest<?> originalRequest,
        Object inputSnapshot,
        SpecialistInputContinuation<?> continuation,
        SpecialistInputRequirement requirement,
        SpecialistSchemaDefinition responseSchema,
        RegisteredSpecialist registered,
        String effectiveProfileHash,
        Instant startedAt,
        int requestNumber
    ) {
        cleanup();
        if (!properties.isEnabled()) {
            throw new InputWaitStoreException(
                "INPUT_WAIT_DISABLED",
                "Typed specialist input waits are disabled."
            );
        }
        if (byRequestId.size() >= properties.getMaxPending()) {
            throw new InputWaitStoreException(
                "INPUT_WAIT_CAPACITY_EXCEEDED",
                "Typed specialist input wait state capacity is exhausted."
            );
        }
        if (requestNumber < 1
            || requestNumber > properties.getMaxRequestsPerInvocation()) {
            throw new InputWaitStoreException(
                "INPUT_WAIT_LIMIT_EXCEEDED",
                "The specialist exceeded its input request limit."
            );
        }
        Objects.requireNonNull(originalRequest, "originalRequest is required");
        Objects.requireNonNull(continuation, "continuation is required");
        Objects.requireNonNull(requirement, "requirement is required");
        Objects.requireNonNull(responseSchema, "responseSchema is required");
        Objects.requireNonNull(registered, "registered specialist is required");
        Objects.requireNonNull(startedAt, "startedAt is required");
        String normalizedInvocationId = requireText(
            invocationId,
            "invocationId"
        );
        Instant now = clock.instant();
        Duration requestedTtl = requirement.ttl() != null
            ? requirement.ttl()
            : properties.getDefaultTtl();
        if (requestedTtl.compareTo(properties.getMaxTtl()) > 0) {
            throw new InputWaitStoreException(
                "INPUT_WAIT_TTL_EXCEEDED",
                "The requested input wait exceeds the deployment limit."
            );
        }
        Instant expiresAt = now.plus(requestedTtl);
        if (originalRequest.deadline() != null
            && originalRequest.deadline().isBefore(expiresAt)) {
            expiresAt = originalRequest.deadline();
        }
        if (!expiresAt.isAfter(now)) {
            throw new InputWaitStoreException(
                "INPUT_WAIT_EXPIRED",
                "The typed input request has already expired."
            );
        }
        int maxAttempts = Math.min(
            requirement.maxAttempts(),
            properties.getMaxAttempts()
        );
        String requestId = "input-request-" + UUID.randomUUID();
        Entry entry = new Entry(
            requestId,
            normalizedInvocationId,
            castRequest(originalRequest),
            inputSnapshot,
            castContinuation(continuation),
            requirement,
            responseSchema,
            registered.contentHash(),
            requireText(effectiveProfileHash, "effectiveProfileHash"),
            ExecutionAccessBinding.from(
                originalRequest.trustedExecutionContext()
            ),
            startedAt,
            now,
            expiresAt,
            maxAttempts,
            requestNumber
        );
        if (byRequestId.putIfAbsent(requestId, entry) != null) {
            throw new IllegalStateException(
                "Duplicate generated input request ID"
            );
        }
        latestByInvocation.put(normalizedInvocationId, requestId);
        return entry;
    }

    Optional<Entry> findByInvocation(
        String invocationId,
        ai.fabric.execution.context.TrustedExecutionContext context
    ) {
        cleanup();
        if (invocationId == null || invocationId.isBlank()) {
            return Optional.empty();
        }
        String requestId = latestByInvocation.get(invocationId.trim());
        Entry entry = requestId != null ? byRequestId.get(requestId) : null;
        return entry != null && entry.accessBinding.matches(context)
            ? Optional.of(entry)
            : Optional.empty();
    }

    Claim claim(
        AIExecutionResumeRequest request,
        String responseHash
    ) {
        cleanup();
        Entry entry = byRequestId.get(request.requestId());
        if (entry == null
            || !entry.invocationId.equals(request.invocationId())
            || !entry.specialistId().equals(request.specialistId())
            || !entry.accessBinding.matches(
                request.trustedExecutionContext()
            )) {
            return new Claim(ClaimStatus.DENIED, null, null);
        }
        synchronized (entry) {
            Instant now = clock.instant();
            if (!now.isBefore(entry.expiresAt)) {
                entry.expire(now, properties.getResultTtl());
                return new Claim(ClaimStatus.EXPIRED, entry, null);
            }
            return switch (entry.status) {
                case WAITING -> {
                    entry.status = State.RESUMING;
                    entry.attempts++;
                    entry.resumeIdempotencyKey = request.idempotencyKey();
                    entry.responseHash = requireText(
                        responseHash,
                        "responseHash"
                    );
                    yield new Claim(ClaimStatus.ACQUIRED, entry, null);
                }
                case RESUMING -> {
                    boolean same = entry.sameResume(
                        request.idempotencyKey(),
                        responseHash
                    );
                    yield new Claim(
                        same
                            ? ClaimStatus.IN_PROGRESS
                            : ClaimStatus.CONFLICT,
                        entry,
                        null
                    );
                }
                case COMPLETED -> {
                    boolean same = entry.sameResume(
                        request.idempotencyKey(),
                        responseHash
                    );
                    yield new Claim(
                        same ? ClaimStatus.REPLAYED : ClaimStatus.CONFLICT,
                        entry,
                        same ? entry.result : null
                    );
                }
                case CANCELLED -> new Claim(
                    ClaimStatus.CANCELLED,
                    entry,
                    null
                );
                case EXPIRED -> new Claim(
                    ClaimStatus.EXPIRED,
                    entry,
                    null
                );
            };
        }
    }

    boolean rejectAttempt(Entry entry) {
        Objects.requireNonNull(entry, "entry is required");
        synchronized (entry) {
            if (entry.status != State.RESUMING) {
                return entry.status == State.WAITING;
            }
            if (entry.attempts >= entry.maxAttempts) {
                entry.expire(clock.instant(), properties.getResultTtl());
                return false;
            }
            entry.status = State.WAITING;
            entry.resumeIdempotencyKey = null;
            entry.responseHash = null;
            return true;
        }
    }

    void complete(Entry entry, AIExecutionResult<?> result) {
        Objects.requireNonNull(entry, "entry is required");
        Objects.requireNonNull(result, "result is required");
        synchronized (entry) {
            if (entry.status != State.RESUMING) {
                throw new IllegalStateException(
                    "Only a claimed input request can complete"
                );
            }
            entry.status = State.COMPLETED;
            entry.result = result;
            entry.retainedUntil = clock.instant().plus(
                properties.getResultTtl()
            );
        }
    }

    void attachWaitingResult(Entry entry, AIExecutionResult<?> result) {
        Objects.requireNonNull(entry, "entry is required");
        Objects.requireNonNull(result, "result is required");
        synchronized (entry) {
            if (entry.status != State.WAITING || !result.waitingForInput()) {
                throw new IllegalStateException(
                    "Waiting state requires a waiting execution result"
                );
            }
            entry.result = result;
        }
    }

    boolean cancel(
        String invocationId,
        ai.fabric.execution.context.TrustedExecutionContext context
    ) {
        Optional<Entry> found = findByInvocation(invocationId, context);
        if (found.isEmpty()) {
            return false;
        }
        Entry entry = found.get();
        synchronized (entry) {
            if (entry.status != State.WAITING) {
                return false;
            }
            entry.status = State.CANCELLED;
            entry.retainedUntil = clock.instant().plus(
                properties.getResultTtl()
            );
            return true;
        }
    }

    ExecutionSnapshot snapshot(Entry entry) {
        synchronized (entry) {
            ExecutionHandleStatus handleStatus = switch (entry.status) {
                case WAITING, RESUMING ->
                    ExecutionHandleStatus.WAITING_FOR_INPUT;
                case COMPLETED -> executionHandleStatus(entry.result);
                case CANCELLED -> ExecutionHandleStatus.CANCELLED;
                case EXPIRED -> ExecutionHandleStatus.EXPIRED;
            };
            String failureReason = switch (entry.status) {
                case CANCELLED -> "CANCELLED";
                case EXPIRED -> "INPUT_WAIT_EXPIRED";
                default -> entry.result != null
                    && entry.result.failure() != null
                        ? entry.result.failure().reason()
                        : null;
            };
            return new ExecutionSnapshot(
                new ExecutionHandle(
                    entry.invocationId,
                    ExecutionDurability.EPHEMERAL,
                    handleStatus,
                    entry.originalRequest.deadline(),
                    entry.status == State.WAITING
                            || entry.status == State.RESUMING
                        ? entry.expiresAt
                        : entry.retainedUntil,
                    failureReason
                ),
                entry.status == State.EXPIRED
                        || entry.status == State.CANCELLED
                    ? null
                    : entry.result
            );
        }
    }

    private ExecutionHandleStatus executionHandleStatus(
        AIExecutionResult<?> result
    ) {
        if (result == null) {
            return ExecutionHandleStatus.FAILED;
        }
        return switch (result.status()) {
            case SUCCEEDED -> ExecutionHandleStatus.SUCCEEDED;
            case WAITING_FOR_INPUT -> ExecutionHandleStatus.WAITING_FOR_INPUT;
            case CANCELLED -> ExecutionHandleStatus.CANCELLED;
            default -> ExecutionHandleStatus.FAILED;
        };
    }

    private void cleanup() {
        Instant now = clock.instant();
        byRequestId.values().removeIf(entry -> {
            synchronized (entry) {
                if (entry.active() && !now.isBefore(entry.expiresAt)) {
                    entry.expire(now, properties.getResultTtl());
                }
                if (entry.active() || now.isBefore(entry.retainedUntil)) {
                    return false;
                }
                latestByInvocation.remove(
                    entry.invocationId,
                    entry.requestId
                );
                return true;
            }
        });
    }

    @SuppressWarnings("unchecked")
    private AIExecutionRequest<Object> castRequest(
        AIExecutionRequest<?> request
    ) {
        return (AIExecutionRequest<Object>) request;
    }

    @SuppressWarnings("unchecked")
    private SpecialistInputContinuation<Object> castContinuation(
        SpecialistInputContinuation<?> continuation
    ) {
        return (SpecialistInputContinuation<Object>) continuation;
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    enum ClaimStatus {
        ACQUIRED,
        REPLAYED,
        DENIED,
        EXPIRED,
        CONFLICT,
        IN_PROGRESS,
        CANCELLED
    }

    record Claim(
        ClaimStatus status,
        Entry entry,
        AIExecutionResult<?> replayedResult
    ) {}

    static final class Entry {
        private final String requestId;
        private final String invocationId;
        private final AIExecutionRequest<Object> originalRequest;
        private final Object inputSnapshot;
        private final SpecialistInputContinuation<Object> continuation;
        private final SpecialistInputRequirement requirement;
        private final SpecialistSchemaDefinition responseSchema;
        private final String specialistContentHash;
        private final String effectiveProfileHash;
        private final ExecutionAccessBinding accessBinding;
        private final Instant startedAt;
        private final Instant createdAt;
        private final Instant expiresAt;
        private final int maxAttempts;
        private final int requestNumber;
        private volatile State status = State.WAITING;
        private volatile int attempts;
        private volatile String resumeIdempotencyKey;
        private volatile String responseHash;
        private volatile AIExecutionResult<?> result;
        private volatile Instant retainedUntil = Instant.MAX;

        private Entry(
            String requestId,
            String invocationId,
            AIExecutionRequest<Object> originalRequest,
            Object inputSnapshot,
            SpecialistInputContinuation<Object> continuation,
            SpecialistInputRequirement requirement,
            SpecialistSchemaDefinition responseSchema,
            String specialistContentHash,
            String effectiveProfileHash,
            ExecutionAccessBinding accessBinding,
            Instant startedAt,
            Instant createdAt,
            Instant expiresAt,
            int maxAttempts,
            int requestNumber
        ) {
            this.requestId = requestId;
            this.invocationId = invocationId;
            this.originalRequest = originalRequest;
            this.inputSnapshot = inputSnapshot;
            this.continuation = continuation;
            this.requirement = requirement;
            this.responseSchema = responseSchema;
            this.specialistContentHash = specialistContentHash;
            this.effectiveProfileHash = effectiveProfileHash;
            this.accessBinding = accessBinding;
            this.startedAt = startedAt;
            this.createdAt = createdAt;
            this.expiresAt = expiresAt;
            this.maxAttempts = maxAttempts;
            this.requestNumber = requestNumber;
        }

        String requestId() {
            return requestId;
        }

        String invocationId() {
            return invocationId;
        }

        AIExecutionRequest<Object> originalRequest() {
            return originalRequest;
        }

        Object inputSnapshot() {
            return inputSnapshot;
        }

        SpecialistInputContinuation<Object> continuation() {
            return continuation;
        }

        SpecialistInputRequirement requirement() {
            return requirement;
        }

        SpecialistSchemaDefinition responseSchema() {
            return responseSchema;
        }

        String specialistContentHash() {
            return specialistContentHash;
        }

        String effectiveProfileHash() {
            return effectiveProfileHash;
        }

        Instant startedAt() {
            return startedAt;
        }

        Instant createdAt() {
            return createdAt;
        }

        Instant expiresAt() {
            return expiresAt;
        }

        int maxAttempts() {
            return maxAttempts;
        }

        int requestNumber() {
            return requestNumber;
        }

        NeedsUserInput needsUserInput() {
            return result != null ? result.needsUserInput() : null;
        }

        private ai.fabric.execution.specialist.SpecialistId specialistId() {
            return originalRequest.specialistId();
        }

        private boolean active() {
            return status == State.WAITING || status == State.RESUMING;
        }

        private boolean sameResume(
            String idempotencyKey,
            String candidateResponseHash
        ) {
            return Objects.equals(
                    resumeIdempotencyKey,
                    idempotencyKey
                )
                && Objects.equals(responseHash, candidateResponseHash);
        }

        private void expire(Instant now, Duration resultTtl) {
            status = State.EXPIRED;
            retainedUntil = now.plus(resultTtl);
        }
    }

    private enum State {
        WAITING,
        RESUMING,
        COMPLETED,
        CANCELLED,
        EXPIRED
    }

    static final class InputWaitStoreException extends RuntimeException {
        private final String reason;

        InputWaitStoreException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
