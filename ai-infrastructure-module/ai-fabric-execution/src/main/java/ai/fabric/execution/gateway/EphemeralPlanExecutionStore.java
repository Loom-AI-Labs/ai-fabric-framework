package ai.fabric.execution.gateway;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.plan.ExecutionPlanId;
import ai.fabric.execution.plan.PlanExecutionFailure;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionResumeRequest;
import ai.fabric.execution.plan.PlanExecutionSnapshot;
import ai.fabric.execution.plan.PlanExecutionStatus;
import ai.fabric.execution.plan.PlanStepTrace;
import ai.fabric.execution.plan.ParallelPlanStep;
import ai.fabric.execution.plan.RegisteredExecutionPlan;
import ai.fabric.execution.plan.SpecialistPlanStep;
import ai.fabric.execution.specialist.SpecialistId;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bounded process-local state for fixed plan checkpoints.
 */
final class EphemeralPlanExecutionStore {

    private final Clock clock;
    private final AIExecutionProperties.Plans properties;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Map<String, String> idempotencyKeys =
        new ConcurrentHashMap<>();

    EphemeralPlanExecutionStore(
        Clock clock,
        AIExecutionProperties.Plans properties
    ) {
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.properties = Objects.requireNonNull(
            properties,
            "properties are required"
        );
    }

    synchronized Entry create(
        String executionId,
        RegisteredExecutionPlan plan,
        PlanExecutionRequest<?> request,
        Instant deadline,
        String inputHash
    ) {
        cleanup();
        if (entries.size() >= properties.getMaxActive()) {
            throw new PlanStoreException(
                "PLAN_CAPACITY_EXCEEDED",
                "Execution plan state capacity is exhausted."
            );
        }
        Objects.requireNonNull(plan, "plan is required");
        Objects.requireNonNull(request, "request is required");
        String id = requireText(executionId, "executionId");
        String idempotencyKey = request.idempotencyKey();
        if (idempotencyKey != null) {
            String existing = idempotencyKeys.get(idempotencyKey);
            if (existing != null) {
                throw new DuplicateIdempotencyKeyException(existing);
            }
        }
        Instant now = clock.instant();
        Entry entry = new Entry(
            id,
            plan.id(),
            plan.contentHash(),
            request.input(),
            requireText(inputHash, "inputHash"),
            request.trustedExecutionContext(),
            deadline,
            idempotencyKey,
            now,
            now.plus(properties.getResultTtl())
        );
        if (entries.putIfAbsent(id, entry) != null) {
            throw new IllegalStateException(
                "Duplicate generated plan execution ID"
            );
        }
        if (idempotencyKey != null) {
            String existing = idempotencyKeys.putIfAbsent(
                idempotencyKey,
                id
            );
            if (existing != null) {
                entries.remove(id);
                throw new DuplicateIdempotencyKeyException(existing);
            }
        }
        return entry;
    }

    Optional<Entry> find(
        String executionId,
        TrustedExecutionContext context
    ) {
        cleanup();
        if (executionId == null || executionId.isBlank()) {
            return Optional.empty();
        }
        Entry entry = entries.get(executionId.trim());
        if (entry == null || !entry.accessBinding.matches(context)) {
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    Optional<Entry> find(String executionId) {
        cleanup();
        return Optional.ofNullable(entries.get(executionId));
    }

    Claim claimResume(
        PlanExecutionResumeRequest request,
        String responseHash
    ) {
        cleanup();
        Entry entry = entries.get(request.executionId());
        if (entry == null
            || !entry.accessBinding.matches(
                request.trustedExecutionContext()
            )) {
            return new Claim(ClaimStatus.DENIED, null, null);
        }
        synchronized (entry) {
            ResumeRecord replay = entry.resumeHistory.get(
                request.idempotencyKey()
            );
            if (replay != null) {
                boolean same = replay.requestId.equals(request.requestId())
                    && replay.responseHash.equals(responseHash);
                return new Claim(
                    same ? ClaimStatus.REPLAYED : ClaimStatus.CONFLICT,
                    entry,
                    same ? replay.result : null
                );
            }
            Instant now = clock.instant();
            if (entry.expired(now)) {
                expire(entry, now, "PLAN_INPUT_WAIT_EXPIRED");
                return new Claim(ClaimStatus.EXPIRED, entry, null);
            }
            if (entry.status == PlanExecutionStatus.CANCELLED) {
                return new Claim(ClaimStatus.CANCELLED, entry, null);
            }
            if (entry.terminal()) {
                return new Claim(ClaimStatus.DENIED, entry, null);
            }
            if (entry.resumeInProgress) {
                boolean same =
                    Objects.equals(
                        entry.resumeIdempotencyKey,
                        request.idempotencyKey()
                    )
                    && Objects.equals(
                        entry.resumeResponseHash,
                        responseHash
                    )
                    && Objects.equals(
                        entry.resumeRequestId,
                        request.requestId()
                    );
                return new Claim(
                    same
                        ? ClaimStatus.IN_PROGRESS
                        : ClaimStatus.CONFLICT,
                    entry,
                    null
                );
            }
            if (entry.status != PlanExecutionStatus.WAITING_FOR_INPUT
                || !Objects.equals(
                    entry.activeInputRequestId,
                    request.requestId()
                )) {
                return new Claim(ClaimStatus.DENIED, entry, null);
            }
            entry.resumeInProgress = true;
            entry.resumeIdempotencyKey = request.idempotencyKey();
            entry.resumeResponseHash = requireText(
                responseHash,
                "responseHash"
            );
            entry.resumeRequestId = request.requestId();
            return new Claim(ClaimStatus.ACQUIRED, entry, null);
        }
    }

    void releaseResume(Entry entry) {
        synchronized (entry) {
            entry.clearResumeClaim();
        }
    }

    void recordResume(
        Entry entry,
        String requestId,
        String idempotencyKey,
        String responseHash,
        PlanExecutionResult<?> result
    ) {
        synchronized (entry) {
            if (!entry.resumeInProgress
                || !Objects.equals(entry.resumeRequestId, requestId)
                || !Objects.equals(
                    entry.resumeIdempotencyKey,
                    idempotencyKey
                )
                || !Objects.equals(
                    entry.resumeResponseHash,
                    responseHash
                )) {
                throw new IllegalStateException(
                    "Only the claimed plan resume can be recorded"
                );
            }
            entry.resumeHistory.put(
                idempotencyKey,
                new ResumeRecord(requestId, responseHash, result)
            );
            entry.clearResumeClaim();
        }
    }

    boolean checkpoint(
        Entry entry,
        int stepIndex,
        SpecialistPlanStep step,
        AIExecutionResult<?> result
    ) {
        synchronized (entry) {
            if (entry.terminal()
                || entry.status == PlanExecutionStatus.CANCELLED
                || stepIndex != entry.nextStepIndex
                || !result.succeeded()) {
                return false;
            }
            if (entry.completed.containsKey(step.id())) {
                return false;
            }
            entry.completed.put(
                step.id(),
                new CompletedStep(
                    step,
                    result.output(),
                    trace(entry, null, step, result)
                )
            );
            entry.nextStepIndex++;
            entry.activeStepId = null;
            entry.activeSpecialistId = null;
            entry.activeInvocationId = null;
            entry.activeInputRequestId = null;
            entry.status = PlanExecutionStatus.RUNNING;
            return true;
        }
    }

    boolean beginParallel(
        Entry entry,
        int stageIndex,
        ParallelPlanStep step
    ) {
        synchronized (entry) {
            if (entry.terminal()
                || entry.status == PlanExecutionStatus.CANCELLED
                || stageIndex != entry.nextStepIndex
                || entry.activeStepId != null) {
                return false;
            }
            entry.activeStepId = step.id();
            entry.activeSpecialistId = null;
            entry.activeInvocationId = null;
            entry.activeInputRequestId = null;
            return true;
        }
    }

    boolean checkpointParallel(
        Entry entry,
        int stageIndex,
        ParallelPlanStep parallel,
        Map<String, AIExecutionResult<?>> results
    ) {
        synchronized (entry) {
            if (entry.terminal()
                || entry.status == PlanExecutionStatus.CANCELLED
                || stageIndex != entry.nextStepIndex
                || !parallel.id().equals(entry.activeStepId)
                || results == null
                || results.size() != parallel.branches().size()) {
                return false;
            }
            for (SpecialistPlanStep branch : parallel.branches()) {
                AIExecutionResult<?> result = results.get(branch.id());
                if (result == null
                    || !result.succeeded()
                    || !branch.specialistId().equals(result.specialistId())
                    || result.output() == null
                    || !branch.outputType().isInstance(result.output())
                    || entry.completed.containsKey(branch.id())) {
                    return false;
                }
            }
            for (SpecialistPlanStep branch : parallel.branches()) {
                AIExecutionResult<?> result = results.get(branch.id());
                entry.completed.put(
                    branch.id(),
                    new CompletedStep(
                        branch,
                        result.output(),
                        trace(entry, parallel.id(), branch, result)
                    )
                );
            }
            entry.nextStepIndex++;
            entry.activeStepId = null;
            entry.activeSpecialistId = null;
            entry.activeInvocationId = null;
            entry.activeInputRequestId = null;
            entry.status = PlanExecutionStatus.RUNNING;
            return true;
        }
    }

    boolean waiting(
        Entry entry,
        int stepIndex,
        SpecialistPlanStep step,
        AIExecutionResult<?> child,
        PlanExecutionResult<?> result
    ) {
        synchronized (entry) {
            if (entry.terminal()
                || entry.status == PlanExecutionStatus.CANCELLED
                || stepIndex != entry.nextStepIndex
                || !child.waitingForInput()) {
                return false;
            }
            entry.status = PlanExecutionStatus.WAITING_FOR_INPUT;
            entry.activeStepId = step.id();
            entry.activeSpecialistId = step.specialistId();
            entry.activeInvocationId = child.invocationId();
            entry.activeInputRequestId =
                child.needsUserInput().requestId();
            entry.expiresAt = earliest(
                child.needsUserInput().expiresAt(),
                entry.deadline
            );
            entry.result = result;
            return true;
        }
    }

    boolean complete(Entry entry, PlanExecutionResult<?> result) {
        synchronized (entry) {
            if (entry.terminal()
                || entry.status == PlanExecutionStatus.CANCELLED) {
                return false;
            }
            entry.status = result.status();
            entry.result = result;
            entry.activeStepId = result.activeStepId();
            entry.activeSpecialistId = null;
            entry.activeInvocationId = null;
            entry.activeInputRequestId = null;
            entry.expiresAt = clock.instant().plus(
                properties.getResultTtl()
            );
            return true;
        }
    }

    boolean cancel(Entry entry) {
        synchronized (entry) {
            if (entry.terminal()) {
                return false;
            }
            Instant now = clock.instant();
            entry.status = PlanExecutionStatus.CANCELLED;
            entry.result = new PlanExecutionResult<>(
                entry.executionId,
                entry.planId,
                entry.planContentHash,
                PlanExecutionStatus.CANCELLED,
                entry.activeStepId,
                null,
                entry.traces(),
                Map.of("durability", ExecutionDurability.EPHEMERAL.name()),
                new PlanExecutionFailure(
                    "PLAN_CANCELLED",
                    "The execution plan was cancelled.",
                    false,
                    entry.activeStepId
                ),
                null,
                entry.startedAt,
                now
            );
            entry.expiresAt = now.plus(properties.getResultTtl());
            entry.clearResumeClaim();
            return true;
        }
    }

    PlanExecutionSnapshot snapshot(Entry entry) {
        synchronized (entry) {
            return new PlanExecutionSnapshot(
                entry.executionId,
                entry.planId,
                entry.status,
                ExecutionDurability.EPHEMERAL,
                entry.activeStepId,
                entry.completed.size(),
                entry.deadline,
                entry.expiresAt,
                entry.result
            );
        }
    }

    private synchronized void cleanup() {
        Instant now = clock.instant();
        entries.values().removeIf(entry -> {
            synchronized (entry) {
                if (!entry.terminal() && entry.expired(now)) {
                    expire(entry, now, "PLAN_DEADLINE_EXCEEDED");
                }
                if (!entry.terminal() || !now.isAfter(entry.expiresAt)) {
                    return false;
                }
                if (entry.idempotencyKey != null) {
                    idempotencyKeys.remove(
                        entry.idempotencyKey,
                        entry.executionId
                    );
                }
                return true;
            }
        });
    }

    private void expire(Entry entry, Instant now, String reason) {
        entry.status = PlanExecutionStatus.DEADLINE_EXCEEDED;
        entry.result = new PlanExecutionResult<>(
            entry.executionId,
            entry.planId,
            entry.planContentHash,
            PlanExecutionStatus.DEADLINE_EXCEEDED,
            entry.activeStepId,
            null,
            entry.traces(),
            Map.of("durability", ExecutionDurability.EPHEMERAL.name()),
            new PlanExecutionFailure(
                reason,
                "The execution plan deadline or input wait has elapsed.",
                true,
                entry.activeStepId
            ),
            null,
            entry.startedAt,
            now
        );
        entry.expiresAt = now.plus(properties.getResultTtl());
        entry.clearResumeClaim();
    }

    private Instant earliest(Instant first, Instant second) {
        if (second == null || first.isBefore(second)) {
            return first;
        }
        return second;
    }

    private PlanStepTrace trace(
        Entry entry,
        String parallelGroupId,
        SpecialistPlanStep step,
        AIExecutionResult<?> result
    ) {
        return new PlanStepTrace(
            step.id(),
            parallelGroupId,
            entry.inputHash,
            result.specialistId(),
            result.invocationId(),
            result.status(),
            result.evidence(),
            result.startedAt(),
            result.completedAt()
        );
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
        PlanExecutionResult<?> replayedResult
    ) {}

    static final class Entry {
        private final String executionId;
        private final ExecutionPlanId planId;
        private final String planContentHash;
        private final Object planInput;
        private final String inputHash;
        private final ExecutionAccessBinding accessBinding;
        private final TrustedExecutionContext initialTrustedContext;
        private final Instant deadline;
        private final String idempotencyKey;
        private final Instant startedAt;
        private final Map<String, CompletedStep> completed =
            new LinkedHashMap<>();
        private final Map<String, ResumeRecord> resumeHistory =
            new LinkedHashMap<>();
        private int nextStepIndex;
        private PlanExecutionStatus status = PlanExecutionStatus.RUNNING;
        private String activeStepId;
        private SpecialistId activeSpecialistId;
        private String activeInvocationId;
        private String activeInputRequestId;
        private boolean resumeInProgress;
        private String resumeIdempotencyKey;
        private String resumeResponseHash;
        private String resumeRequestId;
        private Instant expiresAt;
        private PlanExecutionResult<?> result;

        private Entry(
            String executionId,
            ExecutionPlanId planId,
            String planContentHash,
            Object planInput,
            String inputHash,
            TrustedExecutionContext trustedContext,
            Instant deadline,
            String idempotencyKey,
            Instant startedAt,
            Instant expiresAt
        ) {
            this.executionId = executionId;
            this.planId = planId;
            this.planContentHash = planContentHash;
            this.planInput = planInput;
            this.inputHash = inputHash;
            this.accessBinding = ExecutionAccessBinding.from(trustedContext);
            this.initialTrustedContext = trustedContext;
            this.deadline = deadline;
            this.idempotencyKey = idempotencyKey;
            this.startedAt = startedAt;
            this.expiresAt = deadline != null
                ? deadline
                : expiresAt;
        }

        String executionId() {
            return executionId;
        }

        ExecutionPlanId planId() {
            return planId;
        }

        String planContentHash() {
            return planContentHash;
        }

        Object planInput() {
            return planInput;
        }

        String inputHash() {
            return inputHash;
        }

        TrustedExecutionContext initialTrustedContext() {
            return initialTrustedContext;
        }

        Instant deadline() {
            return deadline;
        }

        Instant startedAt() {
            return startedAt;
        }

        int nextStepIndex() {
            return nextStepIndex;
        }

        String activeStepId() {
            return activeStepId;
        }

        SpecialistId activeSpecialistId() {
            return activeSpecialistId;
        }

        String activeInvocationId() {
            return activeInvocationId;
        }

        String activeInputRequestId() {
            return activeInputRequestId;
        }

        PlanExecutionResult<?> result() {
            return result;
        }

        boolean runnable() {
            return !terminal() && status != PlanExecutionStatus.CANCELLED;
        }

        Map<String, Object> completedOutputs() {
            Map<String, Object> outputs = new LinkedHashMap<>();
            completed.forEach((stepId, step) ->
                outputs.put(stepId, step.output)
            );
            return Map.copyOf(outputs);
        }

        List<PlanStepTrace> traces() {
            List<PlanStepTrace> traces = new ArrayList<>();
            completed.values().forEach(step -> traces.add(step.trace));
            return List.copyOf(traces);
        }

        private boolean expired(Instant now) {
            return expiresAt != null && !now.isBefore(expiresAt);
        }

        private boolean terminal() {
            return status == PlanExecutionStatus.SUCCEEDED
                || status == PlanExecutionStatus.FAILED
                || status == PlanExecutionStatus.DENIED
                || status == PlanExecutionStatus.INVALID
                || status == PlanExecutionStatus.DEADLINE_EXCEEDED
                || status == PlanExecutionStatus.CANCELLED;
        }

        private void clearResumeClaim() {
            resumeInProgress = false;
            resumeIdempotencyKey = null;
            resumeResponseHash = null;
            resumeRequestId = null;
        }
    }

    private record CompletedStep(
        SpecialistPlanStep step,
        Object output,
        PlanStepTrace trace
    ) {}

    private record ResumeRecord(
        String requestId,
        String responseHash,
        PlanExecutionResult<?> result
    ) {}

    static final class PlanStoreException extends RuntimeException {
        private final String reason;

        PlanStoreException(String reason, String message) {
            super(message);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }

    static final class DuplicateIdempotencyKeyException
        extends RuntimeException {

        private final String existingExecutionId;

        DuplicateIdempotencyKeyException(String existingExecutionId) {
            super("Duplicate live plan idempotency key");
            this.existingExecutionId = existingExecutionId;
        }

        String existingExecutionId() {
            return existingExecutionId;
        }
    }
}
