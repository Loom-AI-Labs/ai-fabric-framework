package ai.fabric.intent.orchestration.workflow;

import ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.Effect;
import ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.State;
import ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.Step;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Model-directed single-next-action execution over the host's governed action boundary. */
public final class BoundedWorkflowRunner {
    public static final class PlanningFinding extends RuntimeException {
        private final String detail;
        private final String code;
        private final ai.fabric.dto.ProviderOutcome providerOutcome;
        private final boolean plannerFailure;
        public PlanningFinding() { this("Return a workflow decision with type ACTION, COMPLETE, NEEDS_INPUT or UNSUPPORTED; parameters must be an object and questions an array of strings."); }
        public PlanningFinding(String detail) {
            this("WORKFLOW_DECISION_INVALID", detail, null, false);
        }
        public PlanningFinding(String code, String detail, ai.fabric.dto.ProviderOutcome providerOutcome, boolean plannerFailure) {
            super(code);
            if (detail == null || detail.isBlank() || detail.length() > 2000) throw new IllegalArgumentException("Invalid workflow planning finding");
            this.detail = detail;
            this.code = code; this.providerOutcome = providerOutcome; this.plannerFailure = plannerFailure;
        }
        public String detail() { return detail; }
    }
    public record CompletionFinding(String code, String detail) {
        public CompletionFinding {
            if (code == null || !code.matches("[A-Z][A-Z0-9_]{0,127}") || detail == null || detail.isBlank() || detail.length() > 2000)
                throw new IllegalArgumentException("Invalid workflow completion finding");
        }
    }
    public enum DecisionType { ACTION, DELEGATE, COMPLETE, NEEDS_INPUT, UNSUPPORTED }
    public record Decision(DecisionType type, String action, Map<String, Object> parameters,
                           String message, List<String> questions, WorkflowDelegation delegation) {
        public Decision(DecisionType type, String action, Map<String, Object> parameters, String message, List<String> questions) {
            this(type, action, parameters, message, questions, null);
        }
        public Decision {
            Objects.requireNonNull(type);
            parameters = WorkflowValues.snapshot(parameters);
            questions = questions == null ? List.of() : List.copyOf(questions);
            if (type == DecisionType.ACTION && (action == null || action.isBlank()))
                throw new IllegalArgumentException("WORKFLOW_ACTION_REQUIRED");
            if (type == DecisionType.DELEGATE && delegation == null)
                throw new IllegalArgumentException("WORKFLOW_DELEGATION_REQUIRED");
        }
    }
    public record Outcome(Effect effect, Map<String, Object> observation, String code, boolean correctable) {
        public Outcome {
            Objects.requireNonNull(effect);
            observation = WorkflowValues.snapshot(observation);
            if (correctable && effect != Effect.REJECTED && effect != Effect.EXECUTED)
                throw new IllegalArgumentException("Only typed validation findings are correctable");
        }
    }
    public record Request(String workId, String requestFingerprint, String authorityFingerprint,
                          String definitionFingerprint, Instant deadline, boolean previewOnly) {
        public Request {
            for (String value : List.of(workId, requestFingerprint, authorityFingerprint, definitionFingerprint))
                if (value.isBlank()) throw new IllegalArgumentException("Workflow identity is required");
            Objects.requireNonNull(deadline);
        }
    }
    @FunctionalInterface public interface Planner { Decision next(WorkflowCheckpoint checkpoint, WorkflowAllowance allowance); }
    public interface Actions {
        /** Reauthorize actor, scope, release and resource without making model calls. */
        void verifyAuthority(Request request);
        /** Unknown/denied names throw. Visibility alone never grants execution. */
        boolean isWrite(String action);
        String fingerprint(Map<String, Object> parameters);
        Outcome invoke(Step reserved, WorkflowAllowance allowance);
        /** Consult receiver receipts, never blindly dispatch a reserved write again. */
        Outcome reconcile(Step reserved);
        /** Optional host execution contract, not a semantic-confidence or wording check. */
        default CompletionFinding validateCompletion(WorkflowCheckpoint checkpoint) { return null; }
        default List<WorkflowDelegationProfile> delegatedProfiles() { return List.of(); }
        default void verifyDelegation(WorkflowDelegation delegation) { throw new SecurityException("WORKFLOW_DELEGATION_DENIED"); }
        default void verifyDelegatedAction(WorkflowDelegation delegation, String action, Map<String, Object> parameters) {
            throw new SecurityException("WORKFLOW_DELEGATED_ACTION_DENIED");
        }
        default CompletionFinding validateDelegatedCompletion(WorkflowCheckpoint checkpoint, WorkflowDelegation delegation) { return null; }
    }
    private final WorkflowCheckpointStore store;
    private final Clock clock;
    public BoundedWorkflowRunner(WorkflowCheckpointStore store, Clock clock) {
        this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
    }

    WorkflowCheckpoint prepare(Request request, WorkflowPolicy policy, boolean resume) {
        store.verifyLease(request.workId());
        Instant now = clock.instant();
        Instant ceiling = now.plus(policy.goalMode() ? policy.goal().maxElapsed() : policy.timeout());
        Instant deadline = request.deadline().isBefore(ceiling) ? request.deadline() : ceiling;
        WorkflowCheckpoint found = store.find(request.workId()).orElse(null);
        if (found == null) {
            var progress = policy.goalMode() ? new WorkflowGoalProgress(1, now,
                earlier(deadline, now.plus(policy.timeout())), WorkflowAllowance.Usage.empty(), null, 0, 0, null, List.of()) : null;
            found = new WorkflowCheckpoint(request.workId(), request.requestFingerprint(), request.authorityFingerprint(),
                request.definitionFingerprint(), deadline, 0, WorkflowAllowance.Usage.empty(), List.of(), State.RUNNING, null, List.of(), null, progress);
            if (!store.create(found)) found = store.find(request.workId()).orElseThrow();
        }
        if (!found.requestFingerprint().equals(request.requestFingerprint())
            || !found.authorityFingerprint().equals(request.authorityFingerprint())
            || !found.definitionFingerprint().equals(request.definitionFingerprint())
            || (found.goal() != null) != policy.goalMode())
            throw new IllegalArgumentException("WORKFLOW_REPLAY_CONFLICT");
        if (policy.goalMode() && ((found.state() == State.YIELDED
            && (found.goal().nextEligibleAt() == null || !now.isBefore(found.goal().nextEligibleAt())))
            || (resume && (found.state() == State.PAUSED || found.state() == State.NEEDS_INPUT)))) {
            if (!now.isBefore(found.deadline())) return persist(found, found.usage(), found.steps(), State.PAUSED,
                found.message(), found.questions(), "WORKFLOW_GOAL_DEADLINE");
            var progress = found.goal().nextSegment(now, earlier(found.deadline(), now.plus(policy.timeout())), found.usage());
            if (resume) progress = new WorkflowGoalProgress(progress.segmentNumber(), progress.segmentStartedAt(),
                progress.segmentDeadline(), progress.segmentStartUsage(), null, 0, 0, progress.activeDelegation(), progress.recentFailures());
            found = persist(found, found.usage(), found.steps(), State.RUNNING, found.message(), found.questions(), found.stopCode(), progress);
        }
        if (policy.goalMode() && found.state() == State.COMPLETE
            && (found.usage().minus(found.goal().segmentStartUsage()).modelCalls() >= policy.maxModelCalls()
                || !now.isBefore(found.goal().segmentDeadline()))) {
            // Completion remains established: only final output binding gets a fresh segment, never tools/planning.
            var progress = found.goal().nextSegment(now, earlier(found.deadline(), now.plus(policy.timeout())), found.usage());
            found = persist(found, found.usage(), found.steps(), State.COMPLETE, found.message(), found.questions(), found.stopCode(), progress);
        }
        return found;
    }

    public WorkflowCheckpoint resumeGoal(Request request, WorkflowPolicy policy, Planner planner, Actions actions) {
        if (!policy.goalMode()) throw new IllegalArgumentException("WORKFLOW_GOAL_DISABLED");
        actions.verifyAuthority(request); prepare(request, policy, true);
        return run(request, policy, planner, actions);
    }

    private static Instant earlier(Instant first, Instant second) { return first.isBefore(second) ? first : second; }

    /** Reconcile the original receiver receipt after ordinary confirmation; never dispatch the write again. */
    public WorkflowCheckpoint resumeConfirmed(Request request, WorkflowPolicy policy, Planner planner, Actions actions) {
        if (!policy.enabled()) throw new IllegalArgumentException("WORKFLOW_DISABLED");
        store.verifyLease(request.workId()); actions.verifyAuthority(request);
        var checkpoint = store.find(request.workId()).orElseThrow();
        if (!checkpoint.requestFingerprint().equals(request.requestFingerprint())
            || !checkpoint.authorityFingerprint().equals(request.authorityFingerprint())
            || !checkpoint.definitionFingerprint().equals(request.definitionFingerprint()))
            throw new IllegalArgumentException("WORKFLOW_REPLAY_CONFLICT");
        if (checkpoint.state() != State.CONFIRMATION_REQUIRED) return run(request, policy, planner, actions);
        if (!clock.instant().isBefore(checkpoint.deadline()))
            return persist(checkpoint, checkpoint.usage(), checkpoint.steps(), State.STOPPED, null, List.of(), "WORKFLOW_DEADLINE");
        var steps = new ArrayList<>(checkpoint.steps());
        var pending = steps.getLast();
        if (!pending.write() || pending.effect() != Effect.CONFIRMATION_REQUIRED) throw new IllegalStateException("WORKFLOW_CONFIRMATION_STATE_INVALID");
        actions.isWrite(pending.action());
        var receipt = actions.reconcile(pending);
        if (receipt.effect() == Effect.CONFIRMATION_REQUIRED) return checkpoint;
        steps.set(steps.size() - 1, pending.observed(receipt.effect(), receipt.observation(), receipt.code()));
        var updated = persist(checkpoint, checkpoint.usage(), steps, stateAfter(receipt, policy), null, List.of(), receipt.code());
        return updated.state() == State.RUNNING ? run(request, policy, planner, actions) : updated;
    }

    public WorkflowCheckpoint run(Request request, WorkflowPolicy policy, Planner planner, Actions actions) {
        if (!policy.enabled()) throw new IllegalArgumentException("WORKFLOW_DISABLED");
        store.verifyLease(request.workId()); actions.verifyAuthority(request);
        WorkflowCheckpoint found = prepare(request, policy, false);
        if (found.state() != State.RUNNING) return found;
        AtomicReference<WorkflowCheckpoint> current = new AtomicReference<>(found);
        WorkflowCheckpoint initial = found;
        WorkflowAllowance allowance = WorkflowModelCallScope.current().orElseGet(() -> new WorkflowAllowance(policy, initial.deadline(), clock, initial.usage(), usage -> {
            store.verifyLease(request.workId()); actions.verifyAuthority(request);
            WorkflowCheckpoint old = current.get();
            current.set(persist(old, usage, old.steps(), old.state(), old.message(), old.questions(), old.stopCode()));
        }, initial.goal()));
        try (var ignored = WorkflowModelCallScope.open(allowance)) {
            while (true) {
                allowance.verify(); store.verifyLease(request.workId()); actions.verifyAuthority(request);
                if (policy.goalMode() && (current.get().goal().consecutiveNoProgress() >= policy.goal().maxConsecutiveNoProgress()
                    || current.get().goal().consecutivePlannerFailures() >= policy.goal().maxConsecutivePlannerFailures()))
                    return persist(current.get(), allowance.usage(), current.get().steps(), State.PAUSED, current.get().message(),
                        List.of(), current.get().goal().consecutivePlannerFailures() >= policy.goal().maxConsecutivePlannerFailures()
                            ? "WORKFLOW_PLANNER_UNAVAILABLE" : "WORKFLOW_NO_PROGRESS");
                var steps = new ArrayList<>(current.get().steps());
                boolean pendingDelegation = !steps.isEmpty() && current.get().goal() != null
                    && current.get().goal().activeDelegation() != null
                    && steps.getLast().operationId().equals(current.get().goal().activeDelegation().operationId());
                if (!steps.isEmpty() && !pendingDelegation
                    && (steps.getLast().effect() == Effect.DISPATCHING || steps.getLast().effect() == Effect.OUTCOME_UNKNOWN)) {
                    Step reserved = steps.get(steps.size() - 1);
                    Outcome recovered = actions.reconcile(reserved);
                    steps.set(steps.size() - 1, reserved.observed(recovered.effect(), recovered.observation(), recovered.code()));
                    current.set(persist(current.get(), allowance.usage(), steps, stateAfter(recovered, policy), null, List.of(), recovered.code()));
                    if (current.get().state() != State.RUNNING) return current.get();
                }
                var delegation = current.get().goal() == null ? null : current.get().goal().activeDelegation();
                WorkflowDelegationProfile profile = delegation == null ? null : profile(policy, actions, delegation.task().profileId());
                if (delegation != null) {
                    actions.verifyDelegation(delegation.task());
                    if (!profile.definitionFingerprint().equals(delegation.definitionFingerprint()))
                        return persist(current.get(), allowance.usage(), steps, State.PAUSED, null, List.of(), "WORKFLOW_PROFILE_CHANGED");
                }
                Decision decision;
                try { decision = planner.next(current.get(), allowance); }
                catch (PlanningFinding finding) {
                    var progress = current.get().goal();
                    if (progress != null) progress = progress.observed(false, finding.plannerFailure,
                        new WorkflowGoalProgress.Failure(finding.code, finding.providerOutcome));
                    current.set(persist(current.get(), allowance.usage(), steps, State.RUNNING,
                        finding.detail(), List.of(), finding.code, progress));
                    if (policy.goalMode()) {
                        store.verifyLease(request.workId());
                        actions.verifyAuthority(request);
                    }
                    if (policy.goalMode() && "WORKFLOW_CALL_ERROR".equals(finding.code)) {
                        if (finding.providerOutcome != null && "ADMISSION_DENIED".equals(finding.providerOutcome.transportClassification()))
                            return persist(current.get(), allowance.usage(), steps, State.PAUSED, finding.detail(), List.of(), "WORKFLOW_PROVIDER_ADMISSION_DENIED");
                        if (progress.consecutivePlannerFailures() >= policy.goal().maxConsecutivePlannerFailures())
                            return persist(current.get(), allowance.usage(), steps, State.PAUSED, finding.detail(), List.of(), "WORKFLOW_PLANNER_UNAVAILABLE");
                        var delay = policy.goal().continuationDelay();
                        if (finding.providerOutcome != null && finding.providerOutcome.retryAfterMillis() != null
                            && finding.providerOutcome.retryAfterMillis() > 0) {
                            var instructed = java.time.Duration.ofMillis(finding.providerOutcome.retryAfterMillis());
                            if (instructed.compareTo(delay) > 0) delay = instructed;
                        }
                        return persist(current.get(), allowance.usage(), steps, State.YIELDED, finding.detail(), List.of(), finding.code,
                            progress.scheduled(earlier(current.get().deadline(), clock.instant().plus(delay))));
                    }
                    allowance.correction();
                    continue;
                }
                allowance.verify(); store.verifyLease(request.workId()); actions.verifyAuthority(request);
                if (decision.type() == DecisionType.COMPLETE) {
                    var finding = delegation == null ? actions.validateCompletion(current.get())
                        : actions.validateDelegatedCompletion(current.get(), delegation.task());
                    if (finding != null) {
                        current.set(persist(current.get(), allowance.usage(), steps, State.RUNNING,
                            finding.detail(), List.of(), finding.code(), current.get().goal() == null ? null
                                : current.get().goal().observed(false, false, new WorkflowGoalProgress.Failure(finding.code(), null))));
                        allowance.correction();
                        continue;
                    }
                }
                if (decision.type() == DecisionType.DELEGATE) {
                    if (delegation != null) throw new SecurityException("WORKFLOW_RECURSIVE_DELEGATION_DENIED");
                    var selected = profile(policy, actions, decision.delegation().profileId());
                    actions.verifyDelegation(decision.delegation());
                    allowance.action(false);
                    Step step = new Step(request.workId() + ":step:" + (steps.size() + 1), "profile:" + selected.profileId(), false,
                        Map.of("task", decision.delegation().task(), "workReferences", decision.delegation().workReferences(),
                            "sourceReferences", decision.delegation().sourceReferences()), selected.definitionFingerprint(), Effect.DISPATCHING, Map.of(), null);
                    steps.add(step);
                    var progress = current.get().goal().delegation(new WorkflowGoalProgress.Delegation(step.operationId(),
                        decision.delegation(), selected.definitionFingerprint()));
                    boolean repeated = steps.subList(0, steps.size() - 1).stream().anyMatch(previous ->
                        previous.action().equals(step.action()) && previous.parameters().equals(step.parameters()));
                    if (repeated) progress = progress.observed(false, false, new WorkflowGoalProgress.Failure("WORKFLOW_DELEGATION_REPEATED", null));
                    current.set(persist(current.get(), allowance.usage(), steps, State.RUNNING, null, List.of(), null, progress));
                    continue;
                }
                if (decision.type() != DecisionType.ACTION) {
                    if (delegation != null) {
                        for (int i = 0; i < steps.size(); i++) if (steps.get(i).operationId().equals(delegation.operationId()))
                            steps.set(i, steps.get(i).observed(Effect.EXECUTED, Map.of("state", decision.type().name(),
                                "message", decision.message() == null ? "" : decision.message(), "questions", decision.questions()), null));
                        var progress = current.get().goal().delegation(null);
                        if (decision.type() != DecisionType.COMPLETE) progress = progress.observed(false, false,
                            new WorkflowGoalProgress.Failure("WORKFLOW_DELEGATED_" + decision.type(), null));
                        current.set(persist(current.get(), allowance.usage(), steps, State.RUNNING, null, List.of(), null, progress));
                        continue;
                    }
                    State state = switch (decision.type()) {
                        case COMPLETE -> State.COMPLETE;
                        case NEEDS_INPUT -> State.NEEDS_INPUT;
                        case UNSUPPORTED -> State.UNSUPPORTED;
                        default -> throw new IllegalStateException();
                    };
                    return persist(current.get(), allowance.usage(), steps, state, decision.message(), decision.questions(), null);
                }
                if (profile != null && !profile.allowedActions().contains(decision.action()))
                    throw new SecurityException("WORKFLOW_DELEGATED_ACTION_DENIED");
                if (delegation != null) actions.verifyDelegatedAction(delegation.task(), decision.action(), decision.parameters());
                boolean write = actions.isWrite(decision.action());
                if (write && request.previewOnly())
                    return persist(current.get(), allowance.usage(), steps, State.STOPPED, null, List.of(), "WORKFLOW_PREVIEW_WRITE_DENIED");
                allowance.action(write);
                Step step = new Step(request.workId() + ":step:" + (steps.size() + 1), decision.action(), write,
                    decision.parameters(), actions.fingerprint(decision.parameters()), Effect.DISPATCHING, Map.of(), null);
                steps.add(step);
                current.set(persist(current.get(), allowance.usage(), steps, State.RUNNING, null, List.of(), null));
                allowance.verify(); store.verifyLease(request.workId()); actions.verifyAuthority(request);
                Outcome outcome;
                try { outcome = actions.invoke(step, allowance); }
                catch (RuntimeException failure) {
                    outcome = new Outcome(Effect.OUTCOME_UNKNOWN, Map.of(), "WORKFLOW_DISPATCH_INTERRUPTED", false);
                }
                // Persist actual effects even if cancellation/authority changed during dispatch.
                steps.set(steps.size() - 1, step.observed(outcome.effect(), outcome.observation(), outcome.code()));
                var progress = current.get().goal();
                if (progress != null) {
                    var observed = steps.getLast();
                    boolean duplicate = steps.subList(0, steps.size() - 1).stream().anyMatch(previous ->
                        previous.action().equals(observed.action()) && previous.parameterFingerprint().equals(observed.parameterFingerprint())
                            && previous.effect() == observed.effect() && (observed.write() || previous.observation().equals(observed.observation())));
                    progress = progress.observed(outcome.effect() == Effect.EXECUTED && !outcome.correctable() && !duplicate, false,
                        outcome.code() == null ? null : new WorkflowGoalProgress.Failure(outcome.code(), null));
                }
                try {
                    current.set(persist(current.get(), allowance.usage(), steps, stateAfter(outcome, policy), null, List.of(), outcome.code(), progress));
                } catch (RuntimeException fenced) {
                    store.recordFencedOutcome(request.workId(), steps.getLast());
                    throw fenced;
                }
                if (current.get().state() != State.RUNNING) return current.get();
                if (outcome.correctable()) allowance.correction();
            }
        } catch (WorkflowAllowance.Stop stop) {
            WorkflowCheckpoint old = current.get();
            boolean segment = List.of("WORKFLOW_ACTION_LIMIT", "WORKFLOW_WRITE_LIMIT", "WORKFLOW_MODEL_LIMIT",
                "WORKFLOW_CORRECTION_LIMIT", "WORKFLOW_SEGMENT_DEADLINE").contains(stop.code());
            State state = !policy.goalMode() ? State.STOPPED : segment ? State.YIELDED
                : "WORKFLOW_CANCELLED".equals(stop.code()) ? State.STOPPED : State.PAUSED;
            var progress = old.goal();
            if (state == State.YIELDED) progress = progress.scheduled(clock.instant().plus(policy.goal().continuationDelay()));
            return persist(old, allowance.usage(), old.steps(), state, old.message(), List.of(), stop.code(), progress);
        } catch (ai.fabric.intent.orchestration.control.OrchestrationControlException denied) {
            WorkflowCheckpoint old = current.get();
            return persist(old, allowance.usage(), old.steps(), policy.goalMode() ? State.PAUSED : State.STOPPED,
                null, List.of(), denied.code());
        } catch (RuntimeException failure) {
            WorkflowCheckpoint old = current.get();
            return persist(old, allowance.usage(), old.steps(), State.STOPPED, null, List.of(), "WORKFLOW_EXECUTION_STOPPED");
        }
    }
    private static State stateAfter(Outcome outcome) {
        return switch (outcome.effect()) {
            case EXECUTED -> State.RUNNING;
            case REJECTED -> outcome.correctable() ? State.RUNNING : State.STOPPED;
            case CONFIRMATION_REQUIRED -> State.CONFIRMATION_REQUIRED;
            case OUTCOME_UNKNOWN, DISPATCHING -> State.STOPPED;
        };
    }
    private static State stateAfter(Outcome outcome, WorkflowPolicy policy) {
        if (policy.goalMode() && (outcome.effect() == Effect.OUTCOME_UNKNOWN || outcome.effect() == Effect.DISPATCHING)) return State.PAUSED;
        return stateAfter(outcome);
    }
    private static WorkflowDelegationProfile profile(WorkflowPolicy policy, Actions actions, String id) {
        if (!policy.goalMode() || !policy.goal().delegatedProfiles().contains(id))
            throw new SecurityException("WORKFLOW_DELEGATION_DENIED");
        return actions.delegatedProfiles().stream().filter(p -> p.profileId().equals(id)).findFirst()
            .orElseThrow(() -> new SecurityException("WORKFLOW_PROFILE_UNAVAILABLE"));
    }
    private WorkflowCheckpoint persist(WorkflowCheckpoint old, WorkflowAllowance.Usage usage, List<Step> steps,
                                         State state, String message, List<String> questions, String code) {
        return persist(old, usage, steps, state, message, questions, code, old.goal());
    }
    private WorkflowCheckpoint persist(WorkflowCheckpoint old, WorkflowAllowance.Usage usage, List<Step> steps,
                                         State state, String message, List<String> questions, String code, WorkflowGoalProgress goal) {
        var latest = store.find(old.workId()).orElseThrow();
        if (latest.version() != old.version()) {
            // An inherited root allowance may have reserved calls in the same checkpoint.
            // Merge usage-only changes; never overwrite another step or stop transition.
            if (latest.state() != old.state() || !sameSteps(latest.steps(), old.steps())
                || !Objects.equals(latest.stopCode(), old.stopCode()) || !Objects.equals(latest.goal(), old.goal()))
                throw new IllegalStateException("WORKFLOW_CHECKPOINT_CONFLICT");
            old = latest;
        }
        usage = new WorkflowAllowance.Usage(Math.max(usage.actions(), old.usage().actions()), Math.max(usage.writes(), old.usage().writes()),
            Math.max(usage.modelCalls(), old.usage().modelCalls()), Math.max(usage.corrections(), old.usage().corrections()));
        WorkflowCheckpoint updated = new WorkflowCheckpoint(old.workId(), old.requestFingerprint(), old.authorityFingerprint(),
            old.definitionFingerprint(), old.deadline(), old.version() + 1, usage, steps, state, message, questions, code, goal);
        if (!store.compareAndSet(old, updated)) throw new IllegalStateException("WORKFLOW_CHECKPOINT_CONFLICT");
        return updated;
    }
    private static boolean sameSteps(List<Step> first, List<Step> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            var a = first.get(i); var b = second.get(i);
            if (!a.operationId().equals(b.operationId()) || !a.parameterFingerprint().equals(b.parameterFingerprint())
                || a.effect() != b.effect()) return false;
        }
        return true;
    }
}
