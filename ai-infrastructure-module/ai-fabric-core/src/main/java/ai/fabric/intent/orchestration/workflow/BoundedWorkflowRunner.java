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
        public PlanningFinding() { super("WORKFLOW_DECISION_INVALID"); }
    }
    public enum DecisionType { ACTION, COMPLETE, NEEDS_INPUT, UNSUPPORTED }
    public record Decision(DecisionType type, String action, Map<String, Object> parameters,
                           String message, List<String> questions) {
        public Decision {
            Objects.requireNonNull(type);
            parameters = WorkflowValues.snapshot(parameters);
            questions = questions == null ? List.of() : List.copyOf(questions);
            if (type == DecisionType.ACTION && (action == null || action.isBlank()))
                throw new IllegalArgumentException("WORKFLOW_ACTION_REQUIRED");
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
    }
    private final WorkflowCheckpointStore store;
    private final Clock clock;
    public BoundedWorkflowRunner(WorkflowCheckpointStore store, Clock clock) {
        this.store = Objects.requireNonNull(store); this.clock = Objects.requireNonNull(clock);
    }

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
        var updated = persist(checkpoint, checkpoint.usage(), steps, stateAfter(receipt), null, List.of(), receipt.code());
        return updated.state() == State.RUNNING ? run(request, policy, planner, actions) : updated;
    }

    public WorkflowCheckpoint run(Request request, WorkflowPolicy policy, Planner planner, Actions actions) {
        if (!policy.enabled()) throw new IllegalArgumentException("WORKFLOW_DISABLED");
        store.verifyLease(request.workId()); actions.verifyAuthority(request);
        Instant ceiling = clock.instant().plus(policy.timeout());
        Instant deadline = request.deadline().isBefore(ceiling) ? request.deadline() : ceiling;
        WorkflowCheckpoint found = store.find(request.workId()).orElse(null);
        if (found == null) {
            found = new WorkflowCheckpoint(request.workId(), request.requestFingerprint(), request.authorityFingerprint(),
                request.definitionFingerprint(), deadline, 0, WorkflowAllowance.Usage.empty(), List.of(), State.RUNNING, null, List.of(), null);
            if (!store.create(found)) found = store.find(request.workId()).orElseThrow();
        }
        // Exact hashes protect replay, scope and version boundaries, not semantic quality.
        if (!found.requestFingerprint().equals(request.requestFingerprint())
            || !found.authorityFingerprint().equals(request.authorityFingerprint())
            || !found.definitionFingerprint().equals(request.definitionFingerprint()))
            throw new IllegalArgumentException("WORKFLOW_REPLAY_CONFLICT");
        if (found.state() != State.RUNNING) return found;
        AtomicReference<WorkflowCheckpoint> current = new AtomicReference<>(found);
        WorkflowCheckpoint initial = found;
        WorkflowAllowance allowance = WorkflowModelCallScope.current().orElseGet(() -> new WorkflowAllowance(policy, initial.deadline(), clock, initial.usage(), usage -> {
            store.verifyLease(request.workId()); actions.verifyAuthority(request);
            WorkflowCheckpoint old = current.get();
            current.set(persist(old, usage, old.steps(), old.state(), old.message(), old.questions(), old.stopCode()));
        }));
        try (var ignored = WorkflowModelCallScope.open(allowance)) {
            while (true) {
                allowance.verify(); store.verifyLease(request.workId()); actions.verifyAuthority(request);
                var steps = new ArrayList<>(current.get().steps());
                if (!steps.isEmpty() && steps.get(steps.size() - 1).effect() == Effect.DISPATCHING) {
                    Step reserved = steps.get(steps.size() - 1);
                    Outcome recovered = actions.reconcile(reserved);
                    steps.set(steps.size() - 1, reserved.observed(recovered.effect(), recovered.observation(), recovered.code()));
                    current.set(persist(current.get(), allowance.usage(), steps, stateAfter(recovered), null, List.of(), recovered.code()));
                    if (current.get().state() != State.RUNNING) return current.get();
                }
                Decision decision;
                try { decision = planner.next(current.get(), allowance); }
                catch (PlanningFinding finding) {
                    allowance.correction();
                    current.set(persist(current.get(), allowance.usage(), steps, State.RUNNING,
                        null, List.of(), finding.getMessage()));
                    continue;
                }
                allowance.verify(); store.verifyLease(request.workId()); actions.verifyAuthority(request);
                if (decision.type() != DecisionType.ACTION) {
                    State state = switch (decision.type()) {
                        case COMPLETE -> State.COMPLETE;
                        case NEEDS_INPUT -> State.NEEDS_INPUT;
                        case UNSUPPORTED -> State.UNSUPPORTED;
                        default -> throw new IllegalStateException();
                    };
                    return persist(current.get(), allowance.usage(), steps, state, decision.message(), decision.questions(), null);
                }
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
                current.set(persist(current.get(), allowance.usage(), steps, stateAfter(outcome), null, List.of(), outcome.code()));
                if (current.get().state() != State.RUNNING) return current.get();
                if (outcome.correctable()) allowance.correction();
            }
        } catch (WorkflowAllowance.Stop stop) {
            WorkflowCheckpoint old = current.get();
            return persist(old, allowance.usage(), old.steps(), State.STOPPED, null, List.of(), stop.code());
        } catch (ai.fabric.intent.orchestration.control.OrchestrationControlException denied) {
            WorkflowCheckpoint old = current.get();
            return persist(old, allowance.usage(), old.steps(), State.STOPPED, null, List.of(), denied.code());
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
    private WorkflowCheckpoint persist(WorkflowCheckpoint old, WorkflowAllowance.Usage usage, List<Step> steps,
                                         State state, String message, List<String> questions, String code) {
        var latest = store.find(old.workId()).orElseThrow();
        if (latest.version() != old.version()) {
            // An inherited root allowance may have reserved calls in the same checkpoint.
            // Merge usage-only changes; never overwrite another step or stop transition.
            if (latest.state() != old.state() || !sameSteps(latest.steps(), old.steps())
                || !Objects.equals(latest.stopCode(), old.stopCode()))
                throw new IllegalStateException("WORKFLOW_CHECKPOINT_CONFLICT");
            old = latest;
        }
        usage = new WorkflowAllowance.Usage(Math.max(usage.actions(), old.usage().actions()), Math.max(usage.writes(), old.usage().writes()),
            Math.max(usage.modelCalls(), old.usage().modelCalls()), Math.max(usage.corrections(), old.usage().corrections()));
        WorkflowCheckpoint updated = new WorkflowCheckpoint(old.workId(), old.requestFingerprint(), old.authorityFingerprint(),
            old.definitionFingerprint(), old.deadline(), old.version() + 1, usage, steps, state, message, questions, code);
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
