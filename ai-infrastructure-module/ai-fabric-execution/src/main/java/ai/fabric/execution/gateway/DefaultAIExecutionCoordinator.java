package ai.fabric.execution.gateway;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.ExecutionPlanDefinition;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.plan.PlanComponentRegistry;
import ai.fabric.execution.plan.PlanExecutionFailure;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResult;
import ai.fabric.execution.plan.PlanExecutionResumeRequest;
import ai.fabric.execution.plan.PlanExecutionResumeResult;
import ai.fabric.execution.plan.PlanExecutionResumeStatus;
import ai.fabric.execution.plan.PlanExecutionSnapshot;
import ai.fabric.execution.plan.PlanExecutionStatus;
import ai.fabric.execution.plan.PlanNeedsUserInput;
import ai.fabric.execution.plan.ParallelPlanStep;
import ai.fabric.execution.plan.PlanResultAggregator;
import ai.fabric.execution.plan.PlanStage;
import ai.fabric.execution.plan.PlanStepInputMapper;
import ai.fabric.execution.plan.PlanStepOutputs;
import ai.fabric.execution.plan.PlanStepTrace;
import ai.fabric.execution.plan.RegisteredExecutionPlan;
import ai.fabric.execution.plan.SpecialistPlanStep;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.client.SpecialistResumeInvocation;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Deterministic coordinator for fixed non-interactive specialist plans.
 *
 * <p>Plans execute sequentially unless an explicitly registered and enabled
 * read-only parallel stage is encountered.</p>
 */
public final class DefaultAIExecutionCoordinator
    implements AIExecutionCoordinator {

    private static final Logger log =
        LoggerFactory.getLogger(DefaultAIExecutionCoordinator.class);

    private final ExecutionPlanRegistry planRegistry;
    private final PlanComponentRegistry componentRegistry;
    private final AIExecutionGateway executionGateway;
    private final SpecialistClientFactory specialistClientFactory;
    private final AsyncTaskExecutor parallelExecutor;
    private final CanonicalJsonSupport canonicalJson;
    private final Clock clock;
    private final EphemeralPlanExecutionStore store;
    private final ConcurrentMap<String, ActiveParallelExecution>
        activeParallelExecutions = new ConcurrentHashMap<>();

    public DefaultAIExecutionCoordinator(
        ExecutionPlanRegistry planRegistry,
        PlanComponentRegistry componentRegistry,
        AIExecutionGateway executionGateway,
        SpecialistClientFactory specialistClientFactory,
        AsyncTaskExecutor parallelExecutor,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        AIExecutionProperties.Plans properties
    ) {
        this.planRegistry = Objects.requireNonNull(
            planRegistry,
            "planRegistry is required"
        );
        this.componentRegistry = Objects.requireNonNull(
            componentRegistry,
            "componentRegistry is required"
        );
        this.executionGateway = Objects.requireNonNull(
            executionGateway,
            "executionGateway is required"
        );
        this.specialistClientFactory = Objects.requireNonNull(
            specialistClientFactory,
            "specialistClientFactory is required"
        );
        this.parallelExecutor = Objects.requireNonNull(
            parallelExecutor,
            "parallelExecutor is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
        this.store = new EphemeralPlanExecutionStore(
            clock,
            Objects.requireNonNull(properties, "properties are required")
        );
    }

    @Override
    public <I, O> PlanExecutionResult<O> execute(
        PlanExecutionRequest<I> request
    ) {
        Objects.requireNonNull(request, "request is required");
        String executionId = executionId();
        Instant startedAt = clock.instant();
        RegisteredExecutionPlan plan;
        try {
            plan = planRegistry.require(request.planId());
        } catch (RuntimeException ex) {
            return standaloneFailure(
                executionId,
                request.planId(),
                unknownPlanHash(request.planId()),
                PlanExecutionStatus.INVALID,
                "PLAN_NOT_FOUND",
                "The requested execution plan is not registered.",
                false,
                null,
                startedAt
            );
        }
        ExecutionPlanDefinition<?, ?> definition = plan.definition();
        if (!definition.inputType().isInstance(request.input())) {
            return standaloneFailure(
                executionId,
                plan.id(),
                plan.contentHash(),
                PlanExecutionStatus.INVALID,
                "PLAN_INPUT_TYPE_INVALID",
                "The execution plan input does not satisfy its registered type.",
                false,
                null,
                startedAt
            );
        }
        String inputHash;
        try {
            inputHash = canonicalJson.hashValue(request.input());
        } catch (RuntimeException ex) {
            return standaloneFailure(
                executionId,
                plan.id(),
                plan.contentHash(),
                PlanExecutionStatus.INVALID,
                "PLAN_INPUT_SNAPSHOT_FAILED",
                "The execution plan input could not be snapshotted safely.",
                false,
                null,
                startedAt
            );
        }
        Instant deadline = deadline(request.deadline(), definition, startedAt);
        EphemeralPlanExecutionStore.Entry entry;
        try {
            entry = store.create(
                executionId,
                plan,
                request,
                deadline,
                inputHash
            );
        } catch (
            EphemeralPlanExecutionStore.DuplicateIdempotencyKeyException ex
        ) {
            return standaloneFailure(
                executionId,
                plan.id(),
                plan.contentHash(),
                PlanExecutionStatus.INVALID,
                "DUPLICATE_PLAN_IDEMPOTENCY_KEY",
                "The plan idempotency key is already active.",
                false,
                null,
                startedAt
            );
        } catch (EphemeralPlanExecutionStore.PlanStoreException ex) {
            return standaloneFailure(
                executionId,
                plan.id(),
                plan.contentHash(),
                PlanExecutionStatus.FAILED,
                ex.reason(),
                ex.getMessage(),
                true,
                null,
                startedAt
            );
        }
        if (!clock.instant().isBefore(deadline)) {
            return completeFailure(
                entry,
                PlanExecutionStatus.DEADLINE_EXCEEDED,
                "PLAN_DEADLINE_EXCEEDED",
                "The execution plan deadline has elapsed.",
                true,
                null
            );
        }
        return continueExecution(
            entry,
            plan,
            request.trustedExecutionContext()
        );
    }

    @Override
    public <O> PlanExecutionResumeResult<O> resume(
        PlanExecutionResumeRequest request
    ) {
        Objects.requireNonNull(request, "request is required");
        String responseHash = canonicalJson.hashValue(request.response());
        EphemeralPlanExecutionStore.Claim claim = store.claimResume(
            request,
            responseHash
        );
        return switch (claim.status()) {
            case REPLAYED -> replayed(claim.replayedResult());
            case DENIED -> resumeRejected(
                PlanExecutionResumeStatus.DENIED,
                "PLAN_INPUT_REQUEST_UNAVAILABLE",
                "The plan input request is not available for this trusted context.",
                false
            );
            case EXPIRED -> resumeRejected(
                PlanExecutionResumeStatus.EXPIRED,
                "PLAN_INPUT_REQUEST_EXPIRED",
                "The plan input request has expired.",
                false
            );
            case CONFLICT -> resumeRejected(
                PlanExecutionResumeStatus.REJECTED,
                "PLAN_INPUT_RESUME_CONFLICT",
                "The plan input request was already resumed with different data.",
                false
            );
            case IN_PROGRESS -> resumeRejected(
                PlanExecutionResumeStatus.IN_PROGRESS,
                "PLAN_INPUT_RESUME_IN_PROGRESS",
                "An identical plan input resume is already in progress.",
                true
            );
            case CANCELLED -> resumeRejected(
                PlanExecutionResumeStatus.REJECTED,
                "PLAN_CANCELLED",
                "The execution plan is no longer active.",
                false
            );
            case ACQUIRED -> resumeClaim(
                request,
                responseHash,
                claim.entry()
            );
        };
    }

    @Override
    public Optional<PlanExecutionSnapshot> find(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        return store.find(executionId, trustedExecutionContext)
            .map(store::snapshot);
    }

    @Override
    public boolean cancel(
        String executionId,
        TrustedExecutionContext trustedExecutionContext
    ) {
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        Optional<EphemeralPlanExecutionStore.Entry> found = store.find(
            executionId,
            trustedExecutionContext
        );
        if (found.isEmpty()) {
            return false;
        }
        EphemeralPlanExecutionStore.Entry entry = found.get();
        String childInvocationId = entry.activeInvocationId();
        boolean cancelled = store.cancel(entry);
        if (cancelled) {
            ActiveParallelExecution parallel =
                activeParallelExecutions.get(executionId);
            if (parallel != null) {
                parallel.cancel();
            }
            if (childInvocationId != null) {
                executionGateway.cancel(
                    childInvocationId,
                    trustedExecutionContext
                );
            }
        }
        return cancelled;
    }

    @SuppressWarnings("unchecked")
    private <O> PlanExecutionResumeResult<O> resumeClaim(
        PlanExecutionResumeRequest request,
        String responseHash,
        EphemeralPlanExecutionStore.Entry entry
    ) {
        RegisteredExecutionPlan plan = planRegistry
            .find(entry.planId())
            .orElse(null);
        if (plan == null
            || !plan.contentHash().equals(entry.planContentHash())) {
            PlanExecutionResult<O> failure = completeFailure(
                entry,
                PlanExecutionStatus.DENIED,
                "PLAN_CONTENT_CHANGED",
                "The execution plan content changed while waiting.",
                false,
                entry.activeStepId()
            );
            store.recordResume(
                entry,
                request.requestId(),
                request.idempotencyKey(),
                responseHash,
                failure
            );
            return PlanExecutionResumeResult.resumed(failure);
        }
        if (!clock.instant().isBefore(entry.deadline())) {
            PlanExecutionResult<O> failure = completeFailure(
                entry,
                PlanExecutionStatus.DEADLINE_EXCEEDED,
                "PLAN_DEADLINE_EXCEEDED",
                "The execution plan deadline has elapsed.",
                true,
                entry.activeStepId()
            );
            store.recordResume(
                entry,
                request.requestId(),
                request.idempotencyKey(),
                responseHash,
                failure
            );
            return PlanExecutionResumeResult.resumed(failure);
        }
        int stepIndex = entry.nextStepIndex();
        if (stepIndex < 0
            || stepIndex >= plan.definition().steps().size()) {
            PlanExecutionResult<O> failure = completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_CHECKPOINT_INVALID",
                "The execution plan checkpoint is invalid.",
                false,
                entry.activeStepId()
            );
            store.recordResume(
                entry,
                request.requestId(),
                request.idempotencyKey(),
                responseHash,
                failure
            );
            return PlanExecutionResumeResult.resumed(failure);
        }
        PlanStage stage = plan.definition().steps().get(stepIndex);
        if (!(stage instanceof SpecialistPlanStep step)) {
            PlanExecutionResult<O> failure = completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_CHECKPOINT_INVALID",
                "Parallel plan stages cannot enter an input-wait checkpoint.",
                false,
                stage.id()
            );
            store.recordResume(
                entry,
                request.requestId(),
                request.idempotencyKey(),
                responseHash,
                failure
            );
            return PlanExecutionResumeResult.resumed(failure);
        }
        if (!step.id().equals(entry.activeStepId())
            || !step.specialistId().equals(entry.activeSpecialistId())
            || entry.activeInvocationId() == null) {
            PlanExecutionResult<O> failure = completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_CHECKPOINT_INVALID",
                "The execution plan checkpoint is invalid.",
                false,
                step.id()
            );
            store.recordResume(
                entry,
                request.requestId(),
                request.idempotencyKey(),
                responseHash,
                failure
            );
            return PlanExecutionResumeResult.resumed(failure);
        }

        AIExecutionResumeResult<?> childResume;
        try {
            childResume = bindStep(step).resume(
                new SpecialistResumeInvocation(
                    entry.activeInvocationId(),
                    request.requestId(),
                    request.response(),
                    request.trustedExecutionContext(),
                    request.idempotencyKey()
                )
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Plan execution {} step={} resume failed: {}",
                entry.executionId(),
                step.id(),
                ex.getClass().getSimpleName()
            );
            PlanExecutionResult<O> failure = completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_STEP_RESUME_FAILED",
                "A specialist plan step could not be resumed safely.",
                false,
                step.id()
            );
            store.recordResume(
                entry,
                request.requestId(),
                request.idempotencyKey(),
                responseHash,
                failure
            );
            return PlanExecutionResumeResult.resumed(failure);
        }
        if (childResume.executionResult() == null) {
            if (!childResume.failure().retryable()) {
                PlanExecutionResult<O> failure = completeFailure(
                    entry,
                    terminalResumeStatus(childResume.status()),
                    childResume.failure().reason(),
                    childResume.failure().publicMessage(),
                    false,
                    step.id()
                );
                store.recordResume(
                    entry,
                    request.requestId(),
                    request.idempotencyKey(),
                    responseHash,
                    failure
                );
                return PlanExecutionResumeResult.resumed(failure);
            }
            store.releaseResume(entry);
            return mapRejectedChildResume(childResume);
        }

        PlanExecutionResult<O> result = processChildResult(
            entry,
            plan,
            stepIndex,
            step,
            childResume.executionResult(),
            request.trustedExecutionContext()
        );
        store.recordResume(
            entry,
            request.requestId(),
            request.idempotencyKey(),
            responseHash,
            result
        );
        return PlanExecutionResumeResult.resumed(result);
    }

    @SuppressWarnings("unchecked")
    private <O> PlanExecutionResult<O> continueExecution(
        EphemeralPlanExecutionStore.Entry entry,
        RegisteredExecutionPlan plan,
        TrustedExecutionContext trustedContext
    ) {
        ExecutionPlanDefinition<Object, O> definition =
            (ExecutionPlanDefinition<Object, O>) plan.definition();
        while (entry.nextStepIndex() < definition.steps().size()) {
            if (!entry.runnable()) {
                return (PlanExecutionResult<O>) entry.result();
            }
            if (!clock.instant().isBefore(entry.deadline())) {
                return completeFailure(
                    entry,
                    PlanExecutionStatus.DEADLINE_EXCEEDED,
                    "PLAN_DEADLINE_EXCEEDED",
                    "The execution plan deadline has elapsed.",
                    true,
                    entry.activeStepId()
                );
            }
            int stepIndex = entry.nextStepIndex();
            PlanStage stage = definition.steps().get(stepIndex);
            if (stage instanceof ParallelPlanStep parallel) {
                return executeParallelStage(
                    entry,
                    plan,
                    stepIndex,
                    parallel,
                    trustedContext
                );
            }
            SpecialistPlanStep step = (SpecialistPlanStep) stage;
            Object stepInput;
            try {
                stepInput = mapStepInput(
                    definition,
                    step,
                    entry.planInput(),
                    entry.completedOutputs()
                );
            } catch (RuntimeException ex) {
                log.warn(
                    "Plan execution {} step={} input mapping failed: {}",
                    entry.executionId(),
                    step.id(),
                    ex.getClass().getSimpleName()
                );
                return completeFailure(
                    entry,
                    PlanExecutionStatus.FAILED,
                    "PLAN_STEP_MAPPING_FAILED",
                    "A registered plan step input mapping failed.",
                    false,
                    step.id()
                );
            }
            AIExecutionResult<?> child;
            try {
                child = bindStep(step).execute(
                    new SpecialistInvocation<>(
                        stepInput,
                        trustedContext,
                        null,
                        entry.deadline(),
                        childIdempotencyKey(entry.executionId(), step.id())
                    )
                );
            } catch (RuntimeException ex) {
                log.warn(
                    "Plan execution {} step={} invocation failed: {}",
                    entry.executionId(),
                    step.id(),
                    ex.getClass().getSimpleName()
                );
                return completeFailure(
                    entry,
                    PlanExecutionStatus.FAILED,
                    "PLAN_STEP_INVOCATION_FAILED",
                    "A specialist plan step could not be invoked safely.",
                    false,
                    step.id()
                );
            }
            return processChildResult(
                entry,
                plan,
                stepIndex,
                step,
                child,
                trustedContext
            );
        }

        O output;
        try {
            output = aggregate(
                definition,
                entry.planInput(),
                entry.completedOutputs()
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Plan execution {} aggregation failed: {}",
                entry.executionId(),
                ex.getClass().getSimpleName()
            );
            return completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_AGGREGATION_FAILED",
                "The registered plan result aggregation failed.",
                false,
                null
            );
        }
        PlanExecutionResult<O> result = new PlanExecutionResult<>(
            entry.executionId(),
            plan.id(),
            plan.contentHash(),
            PlanExecutionStatus.SUCCEEDED,
            null,
            output,
            entry.traces(),
            diagnostics(entry, specialistStepCount(definition)),
            null,
            null,
            entry.startedAt(),
            clock.instant()
        );
        if (!store.complete(entry, result)) {
            return (PlanExecutionResult<O>) entry.result();
        }
        log.info(
            "Plan execution {} plan={} status=SUCCEEDED completedSteps={}",
            entry.executionId(),
            plan.id(),
            entry.traces().size()
        );
        return result;
    }

    @SuppressWarnings("unchecked")
    private <O> PlanExecutionResult<O> executeParallelStage(
        EphemeralPlanExecutionStore.Entry entry,
        RegisteredExecutionPlan plan,
        int stageIndex,
        ParallelPlanStep parallel,
        TrustedExecutionContext trustedContext
    ) {
        ExecutionPlanDefinition<Object, O> definition =
            (ExecutionPlanDefinition<Object, O>) plan.definition();
        List<MappedParallelBranch> mappedBranches = new ArrayList<>();
        for (SpecialistPlanStep branch : parallel.branches()) {
            try {
                mappedBranches.add(new MappedParallelBranch(
                    branch,
                    mapStepInput(
                        definition,
                        branch,
                        entry.planInput(),
                        entry.completedOutputs()
                    )
                ));
            } catch (RuntimeException ex) {
                log.warn(
                    "Plan execution {} parallelGroup={} branch={} input mapping failed: {}",
                    entry.executionId(),
                    parallel.id(),
                    branch.id(),
                    ex.getClass().getSimpleName()
                );
                return completeFailure(
                    entry,
                    PlanExecutionStatus.FAILED,
                    "PLAN_PARALLEL_BRANCH_MAPPING_FAILED",
                    "A registered parallel branch input mapping failed.",
                    false,
                    branch.id()
                );
            }
        }
        if (!store.beginParallel(entry, stageIndex, parallel)) {
            return castResult(entry.result());
        }

        ActiveParallelExecution active = new ActiveParallelExecution();
        ActiveParallelExecution existing = activeParallelExecutions.putIfAbsent(
            entry.executionId(),
            active
        );
        if (existing != null) {
            return completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_PARALLEL_STATE_CONFLICT",
                "Parallel plan state could not be acquired safely.",
                true,
                parallel.id()
            );
        }

        ExecutorCompletionService<ParallelBranchCompletion> completion =
            new ExecutorCompletionService<>(parallelExecutor);
        Map<String, AIExecutionResult<?>> results = new LinkedHashMap<>();
        boolean completedSuccessfully = false;
        try {
            for (MappedParallelBranch mapped : mappedBranches) {
                if (!entry.runnable()) {
                    return castResult(entry.result());
                }
                Future<ParallelBranchCompletion> future;
                try {
                    future = completion.submit(() ->
                        invokeParallelBranch(
                            entry,
                            mapped,
                            trustedContext
                        )
                    );
                } catch (RuntimeException ex) {
                    log.warn(
                        "Plan execution {} parallelGroup={} branch submission failed: {}",
                        entry.executionId(),
                        parallel.id(),
                        ex.getClass().getSimpleName()
                    );
                    active.cancel();
                    return completeFailure(
                        entry,
                        PlanExecutionStatus.FAILED,
                        "PLAN_PARALLEL_CAPACITY_REJECTED",
                        "Parallel branch capacity is temporarily unavailable.",
                        true,
                        parallel.id(),
                        parallelTraces(entry, parallel, results)
                    );
                }
                active.add(future);
            }

            while (results.size() < parallel.branches().size()) {
                if (!entry.runnable()) {
                    active.cancel();
                    return castResult(entry.result());
                }
                long remainingNanos = java.time.Duration.between(
                    clock.instant(),
                    entry.deadline()
                ).toNanos();
                if (remainingNanos <= 0) {
                    active.cancel();
                    return completeFailure(
                        entry,
                        PlanExecutionStatus.DEADLINE_EXCEEDED,
                        "PLAN_DEADLINE_EXCEEDED",
                        "The execution plan deadline elapsed during parallel work.",
                        true,
                        parallel.id(),
                        parallelTraces(entry, parallel, results)
                    );
                }
                Future<ParallelBranchCompletion> completedFuture =
                    completion.poll(remainingNanos, TimeUnit.NANOSECONDS);
                if (completedFuture == null) {
                    active.cancel();
                    return completeFailure(
                        entry,
                        PlanExecutionStatus.DEADLINE_EXCEEDED,
                        "PLAN_DEADLINE_EXCEEDED",
                        "The execution plan deadline elapsed during parallel work.",
                        true,
                        parallel.id(),
                        parallelTraces(entry, parallel, results)
                    );
                }
                ParallelBranchCompletion branchCompletion;
                try {
                    branchCompletion = completedFuture.get();
                } catch (CancellationException ex) {
                    active.cancel();
                    if (!entry.runnable()) {
                        return castResult(entry.result());
                    }
                    return completeFailure(
                        entry,
                        PlanExecutionStatus.CANCELLED,
                        "PLAN_CANCELLED",
                        "Parallel plan work was cancelled.",
                        false,
                        parallel.id(),
                        parallelTraces(entry, parallel, results)
                    );
                } catch (ExecutionException ex) {
                    active.cancel();
                    return completeFailure(
                        entry,
                        PlanExecutionStatus.FAILED,
                        "PLAN_PARALLEL_BRANCH_INVOCATION_FAILED",
                        "A parallel specialist branch could not be invoked safely.",
                        false,
                        parallel.id(),
                        parallelTraces(entry, parallel, results)
                    );
                }
                if (branchCompletion.failureType() != null) {
                    active.cancel();
                    return completeFailure(
                        entry,
                        PlanExecutionStatus.FAILED,
                        "PLAN_PARALLEL_BRANCH_INVOCATION_FAILED",
                        "A parallel specialist branch could not be invoked safely.",
                        false,
                        branchCompletion.step().id(),
                        parallelTraces(entry, parallel, results)
                    );
                }
                SpecialistPlanStep branch = branchCompletion.step();
                AIExecutionResult<?> child = branchCompletion.result();
                results.put(branch.id(), child);
                PlanExecutionResult<O> failure = validateParallelBranch(
                    entry,
                    parallel,
                    branch,
                    child,
                    results
                );
                if (failure != null) {
                    active.cancel();
                    return failure;
                }
            }
            if (!store.checkpointParallel(
                    entry,
                    stageIndex,
                    parallel,
                    results
                )) {
                return castResult(entry.result());
            }
            completedSuccessfully = true;
            return continueExecution(entry, plan, trustedContext);
        } catch (InterruptedException ex) {
            active.cancel();
            Thread.currentThread().interrupt();
            return completeFailure(
                entry,
                PlanExecutionStatus.CANCELLED,
                "PLAN_CANCELLED",
                "Parallel plan execution was interrupted.",
                false,
                parallel.id(),
                parallelTraces(entry, parallel, results)
            );
        } finally {
            if (!completedSuccessfully) {
                active.cancel();
            }
            activeParallelExecutions.remove(entry.executionId(), active);
        }
    }

    private ParallelBranchCompletion invokeParallelBranch(
        EphemeralPlanExecutionStore.Entry entry,
        MappedParallelBranch mapped,
        TrustedExecutionContext trustedContext
    ) {
        try {
            AIExecutionResult<?> child = bindStep(mapped.step()).execute(
                new SpecialistInvocation<>(
                    mapped.input(),
                    trustedContext,
                    null,
                    entry.deadline(),
                    childIdempotencyKey(
                        entry.executionId(),
                        mapped.step().id()
                    )
                )
            );
            return ParallelBranchCompletion.succeeded(
                mapped.step(),
                child
            );
        } catch (RuntimeException ex) {
            log.warn(
                "Plan execution {} parallel branch={} invocation failed: {}",
                entry.executionId(),
                mapped.step().id(),
                ex.getClass().getSimpleName()
            );
            return ParallelBranchCompletion.failed(
                mapped.step(),
                ex.getClass().getSimpleName()
            );
        }
    }

    private <O> PlanExecutionResult<O> validateParallelBranch(
        EphemeralPlanExecutionStore.Entry entry,
        ParallelPlanStep parallel,
        SpecialistPlanStep branch,
        AIExecutionResult<?> child,
        Map<String, AIExecutionResult<?>> results
    ) {
        List<PlanStepTrace> traces = parallelTraces(
            entry,
            parallel,
            results
        );
        if (!branch.specialistId().equals(child.specialistId())) {
            return completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_STEP_SPECIALIST_MISMATCH",
                "A parallel branch returned under an unexpected identity.",
                false,
                branch.id(),
                traces
            );
        }
        if (child.succeeded()) {
            if (child.output() == null
                || !branch.outputType().isInstance(child.output())) {
                return completeFailure(
                    entry,
                    PlanExecutionStatus.FAILED,
                    "PLAN_STEP_OUTPUT_TYPE_INVALID",
                    "A parallel branch returned an invalid typed output.",
                    false,
                    branch.id(),
                    traces
                );
            }
            return null;
        }
        if (child.waitingForInput()) {
            return completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_PARALLEL_INPUT_WAIT_UNSUPPORTED",
                "Parallel branches cannot request user input in this runtime.",
                false,
                branch.id(),
                traces
            );
        }
        if (child.status() == AIExecutionStatus.CONFIRMATION_REQUIRED) {
            return completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_PARALLEL_WRITE_PROPOSAL_UNSUPPORTED",
                "Parallel branches cannot propose WRITE actions in this runtime.",
                false,
                branch.id(),
                traces
            );
        }
        PlanExecutionFailure childFailure = child.failure() != null
            ? new PlanExecutionFailure(
                child.failure().reason(),
                child.failure().publicMessage(),
                child.failure().retryable(),
                branch.id()
            )
            : new PlanExecutionFailure(
                "PLAN_CHILD_EXECUTION_FAILED",
                "A parallel specialist branch failed.",
                false,
                branch.id()
            );
        return completeFailure(
            entry,
            mapStatus(child.status()),
            childFailure,
            traces
        );
    }

    private <O> PlanExecutionResult<O> processChildResult(
        EphemeralPlanExecutionStore.Entry entry,
        RegisteredExecutionPlan plan,
        int stepIndex,
        SpecialistPlanStep step,
        AIExecutionResult<?> child,
        TrustedExecutionContext trustedContext
    ) {
        if (!entry.runnable()) {
            return castResult(entry.result());
        }
        if (!step.specialistId().equals(child.specialistId())) {
            return completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_STEP_SPECIALIST_MISMATCH",
                "A specialist plan step returned under an unexpected identity.",
                false,
                step.id(),
                withTrace(entry, entry.traces(), step, child)
            );
        }
        if (child.succeeded()) {
            if (child.output() == null
                || !step.outputType().isInstance(child.output())) {
                return completeFailure(
                    entry,
                    PlanExecutionStatus.FAILED,
                    "PLAN_STEP_OUTPUT_TYPE_INVALID",
                    "A specialist plan step returned an invalid typed output.",
                    false,
                    step.id(),
                    withTrace(entry, entry.traces(), step, child)
                );
            }
            if (!store.checkpoint(entry, stepIndex, step, child)) {
                return castResult(entry.result());
            }
            return continueExecution(entry, plan, trustedContext);
        }
        if (child.waitingForInput()) {
            PlanExecutionResult<O> waiting = new PlanExecutionResult<>(
                entry.executionId(),
                plan.id(),
                plan.contentHash(),
                PlanExecutionStatus.WAITING_FOR_INPUT,
                step.id(),
                null,
                withTrace(entry, entry.traces(), step, child),
                diagnostics(
                    entry,
                    specialistStepCount(plan.definition())
                ),
                null,
                new PlanNeedsUserInput(
                    entry.executionId(),
                    plan.id(),
                    step.id(),
                    child.needsUserInput()
                ),
                entry.startedAt(),
                clock.instant()
            );
            if (!store.waiting(
                    entry,
                    stepIndex,
                    step,
                    child,
                    waiting
                )) {
                return castResult(entry.result());
            }
            log.info(
                "Plan execution {} plan={} step={} status=WAITING_FOR_INPUT",
                entry.executionId(),
                plan.id(),
                step.id()
            );
            return waiting;
        }
        if (child.status() == AIExecutionStatus.CONFIRMATION_REQUIRED) {
            return completeFailure(
                entry,
                PlanExecutionStatus.FAILED,
                "PLAN_WRITE_PROPOSAL_UNSUPPORTED",
                "This plan runtime does not support composed WRITE proposals.",
                false,
                step.id(),
                withTrace(entry, entry.traces(), step, child)
            );
        }
        PlanExecutionFailure childFailure = child.failure() != null
            ? new PlanExecutionFailure(
                child.failure().reason(),
                child.failure().publicMessage(),
                child.failure().retryable(),
                step.id()
            )
            : new PlanExecutionFailure(
                "PLAN_CHILD_EXECUTION_FAILED",
                "A specialist plan step failed.",
                false,
                step.id()
            );
        return completeFailure(
            entry,
            mapStatus(child.status()),
            childFailure,
            withTrace(entry, entry.traces(), step, child)
        );
    }

    private Object mapStepInput(
        ExecutionPlanDefinition<?, ?> definition,
        SpecialistPlanStep step,
        Object planInput,
        Map<String, Object> completedOutputs
    ) {
        PlanStepInputMapper<?, ?> mapper =
            componentRegistry.requireMapper(step.inputMapperId());
        PlanStepOutputs approved = approvedOutputs(
            mapper.requiredStepOutputs(),
            completedOutputs
        );
        return mapUnchecked(mapper, definition.inputType(), planInput, approved);
    }

    @SuppressWarnings("unchecked")
    private Object mapUnchecked(
        PlanStepInputMapper<?, ?> mapper,
        Class<?> planInputType,
        Object planInput,
        PlanStepOutputs approved
    ) {
        PlanStepInputMapper<Object, Object> typed =
            (PlanStepInputMapper<Object, Object>) mapper;
        Object mapped = typed.map(
            planInputType.cast(planInput),
            approved
        );
        if (mapped == null || !mapper.stepInputType().isInstance(mapped)) {
            throw new IllegalArgumentException(
                "Plan mapper returned an invalid specialist input"
            );
        }
        return mapped;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private SpecialistClient<Object, Object> bindStep(
        SpecialistPlanStep step
    ) {
        return (SpecialistClient<Object, Object>)
            specialistClientFactory.bind(
                step.specialistId(),
                (Class) step.inputType(),
                (Class) step.outputType()
            );
    }

    @SuppressWarnings("unchecked")
    private <O> O aggregate(
        ExecutionPlanDefinition<?, O> definition,
        Object planInput,
        Map<String, Object> completedOutputs
    ) {
        PlanResultAggregator<?, ?> aggregator =
            componentRegistry.requireAggregator(definition.aggregatorId());
        PlanStepOutputs approved = approvedOutputs(
            aggregator.requiredStepOutputs(),
            completedOutputs
        );
        PlanResultAggregator<Object, Object> typed =
            (PlanResultAggregator<Object, Object>) aggregator;
        Object output = typed.aggregate(
            definition.inputType().cast(planInput),
            approved
        );
        if (output == null || !definition.outputType().isInstance(output)) {
            throw new IllegalArgumentException(
                "Plan aggregator returned an invalid output"
            );
        }
        return definition.outputType().cast(output);
    }

    private PlanStepOutputs approvedOutputs(
        Map<String, Class<?>> required,
        Map<String, Object> completed
    ) {
        Map<String, Object> approved = new LinkedHashMap<>();
        if (required != null) {
            required.forEach((stepId, type) -> {
                Object output = completed.get(stepId);
                if (output == null || !type.isInstance(output)) {
                    throw new IllegalArgumentException(
                        "Required typed step output is unavailable"
                    );
                }
                approved.put(stepId, output);
            });
        }
        return new PlanStepOutputs(approved);
    }

    private <O> PlanExecutionResult<O> completeFailure(
        EphemeralPlanExecutionStore.Entry entry,
        PlanExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        String stepId
    ) {
        return completeFailure(
            entry,
            status,
            new PlanExecutionFailure(
                reason,
                message,
                retryable,
                stepId
            ),
            entry.traces()
        );
    }

    private <O> PlanExecutionResult<O> completeFailure(
        EphemeralPlanExecutionStore.Entry entry,
        PlanExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        String stepId,
        List<PlanStepTrace> traces
    ) {
        return completeFailure(
            entry,
            status,
            new PlanExecutionFailure(
                reason,
                message,
                retryable,
                stepId
            ),
            traces
        );
    }

    private <O> PlanExecutionResult<O> completeFailure(
        EphemeralPlanExecutionStore.Entry entry,
        PlanExecutionStatus status,
        PlanExecutionFailure failure,
        List<PlanStepTrace> traces
    ) {
        PlanExecutionResult<O> result = new PlanExecutionResult<>(
            entry.executionId(),
            entry.planId(),
            entry.planContentHash(),
            status,
            failure.stepId(),
            null,
            traces,
            Map.of("durability", ExecutionDurability.EPHEMERAL.name()),
            failure,
            null,
            entry.startedAt(),
            clock.instant()
        );
        if (!store.complete(entry, result)) {
            return castResult(entry.result());
        }
        return result;
    }

    private <O> PlanExecutionResult<O> standaloneFailure(
        String executionId,
        ai.fabric.execution.plan.ExecutionPlanId planId,
        String planHash,
        PlanExecutionStatus status,
        String reason,
        String message,
        boolean retryable,
        String stepId,
        Instant startedAt
    ) {
        return new PlanExecutionResult<>(
            executionId,
            planId,
            planHash,
            status,
            stepId,
            null,
            List.of(),
            Map.of("durability", ExecutionDurability.EPHEMERAL.name()),
            new PlanExecutionFailure(
                reason,
                message,
                retryable,
                stepId
            ),
            null,
            startedAt,
            clock.instant()
        );
    }

    private List<PlanStepTrace> withTrace(
        EphemeralPlanExecutionStore.Entry entry,
        List<PlanStepTrace> traces,
        SpecialistPlanStep step,
        AIExecutionResult<?> child
    ) {
        List<PlanStepTrace> combined = new ArrayList<>(traces);
        combined.add(new PlanStepTrace(
            step.id(),
            null,
            entry.inputHash(),
            child.specialistId(),
            child.invocationId(),
            child.status(),
            child.evidence(),
            child.startedAt(),
            child.completedAt()
        ));
        return List.copyOf(combined);
    }

    private List<PlanStepTrace> parallelTraces(
        EphemeralPlanExecutionStore.Entry entry,
        ParallelPlanStep parallel,
        Map<String, AIExecutionResult<?>> results
    ) {
        List<PlanStepTrace> combined = new ArrayList<>(entry.traces());
        for (SpecialistPlanStep branch : parallel.branches()) {
            AIExecutionResult<?> result = results.get(branch.id());
            if (result == null) {
                continue;
            }
            combined.add(new PlanStepTrace(
                branch.id(),
                parallel.id(),
                entry.inputHash(),
                result.specialistId(),
                result.invocationId(),
                result.status(),
                result.evidence(),
                result.startedAt(),
                result.completedAt()
            ));
        }
        return List.copyOf(combined);
    }

    private int specialistStepCount(
        ExecutionPlanDefinition<?, ?> definition
    ) {
        int count = 0;
        for (PlanStage stage : definition.steps()) {
            count += stage instanceof SpecialistPlanStep
                ? 1
                : ((ParallelPlanStep) stage).branches().size();
        }
        return count;
    }

    private Map<String, Object> diagnostics(
        EphemeralPlanExecutionStore.Entry entry,
        int stepCount
    ) {
        return Map.of(
            "durability",
            ExecutionDurability.EPHEMERAL.name(),
            "completedSteps",
            entry.traces().size(),
            "stepCount",
            stepCount
        );
    }

    private PlanExecutionStatus mapStatus(AIExecutionStatus status) {
        return switch (status) {
            case DENIED -> PlanExecutionStatus.DENIED;
            case INVALID -> PlanExecutionStatus.INVALID;
            case DEADLINE_EXCEEDED ->
                PlanExecutionStatus.DEADLINE_EXCEEDED;
            case CANCELLED -> PlanExecutionStatus.CANCELLED;
            default -> PlanExecutionStatus.FAILED;
        };
    }

    private PlanExecutionStatus terminalResumeStatus(
        AIExecutionResumeStatus status
    ) {
        return switch (status) {
            case EXPIRED -> PlanExecutionStatus.DEADLINE_EXCEEDED;
            case DENIED -> PlanExecutionStatus.DENIED;
            default -> PlanExecutionStatus.FAILED;
        };
    }

    private <O> PlanExecutionResumeResult<O> mapRejectedChildResume(
        AIExecutionResumeResult<?> child
    ) {
        AIExecutionFailure failure = child.failure();
        PlanExecutionResumeStatus status = switch (child.status()) {
            case DENIED -> PlanExecutionResumeStatus.DENIED;
            case EXPIRED -> PlanExecutionResumeStatus.EXPIRED;
            case IN_PROGRESS -> PlanExecutionResumeStatus.IN_PROGRESS;
            default -> PlanExecutionResumeStatus.REJECTED;
        };
        return resumeRejected(
            status,
            failure.reason(),
            failure.publicMessage(),
            failure.retryable()
        );
    }

    private <O> PlanExecutionResumeResult<O> resumeRejected(
        PlanExecutionResumeStatus status,
        String reason,
        String message,
        boolean retryable
    ) {
        return PlanExecutionResumeResult.rejected(
            status,
            reason,
            message,
            retryable
        );
    }

    @SuppressWarnings("unchecked")
    private <O> PlanExecutionResumeResult<O> replayed(
        PlanExecutionResult<?> result
    ) {
        return PlanExecutionResumeResult.replayed(
            (PlanExecutionResult<O>) result
        );
    }

    @SuppressWarnings("unchecked")
    private <O> PlanExecutionResult<O> castResult(
        PlanExecutionResult<?> result
    ) {
        return (PlanExecutionResult<O>) result;
    }

    private Instant deadline(
        Instant requested,
        ExecutionPlanDefinition<?, ?> definition,
        Instant startedAt
    ) {
        Instant planLimit = startedAt.plus(definition.maximumDuration());
        if (requested == null || planLimit.isBefore(requested)) {
            return planLimit;
        }
        return requested;
    }

    private String childIdempotencyKey(
        String executionId,
        String stepId
    ) {
        return "plan-step-" + CanonicalJsonSupport.sha256(
            executionId + "\n" + stepId
        );
    }

    private String unknownPlanHash(
        ai.fabric.execution.plan.ExecutionPlanId id
    ) {
        return CanonicalJsonSupport.sha256("unknown-plan\n" + id);
    }

    private String executionId() {
        return "plan-execution-" + UUID.randomUUID();
    }

    private record MappedParallelBranch(
        SpecialistPlanStep step,
        Object input
    ) {}

    private record ParallelBranchCompletion(
        SpecialistPlanStep step,
        AIExecutionResult<?> result,
        String failureType
    ) {
        private static ParallelBranchCompletion succeeded(
            SpecialistPlanStep step,
            AIExecutionResult<?> result
        ) {
            return new ParallelBranchCompletion(
                Objects.requireNonNull(step, "step is required"),
                Objects.requireNonNull(result, "result is required"),
                null
            );
        }

        private static ParallelBranchCompletion failed(
            SpecialistPlanStep step,
            String failureType
        ) {
            return new ParallelBranchCompletion(
                Objects.requireNonNull(step, "step is required"),
                null,
                Objects.requireNonNull(
                    failureType,
                    "failureType is required"
                )
            );
        }
    }

    private static final class ActiveParallelExecution {

        private final List<Future<?>> futures = new ArrayList<>();
        private boolean cancelled;

        private synchronized void add(Future<?> future) {
            Future<?> required = Objects.requireNonNull(
                future,
                "future is required"
            );
            if (cancelled) {
                required.cancel(true);
                return;
            }
            futures.add(required);
        }

        private synchronized void cancel() {
            cancelled = true;
            futures.forEach(future -> future.cancel(true));
        }
    }
}
