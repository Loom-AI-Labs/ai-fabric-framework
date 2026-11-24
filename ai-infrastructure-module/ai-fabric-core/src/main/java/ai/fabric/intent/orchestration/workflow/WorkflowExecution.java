package ai.fabric.intent.orchestration.workflow;

import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import java.util.Map;
import java.util.Objects;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Trusted host binding. Never deserialize this object from a public chat request. */
public record WorkflowExecution(BoundedWorkflowRunner.Request request, WorkflowPolicy policy,
                                WorkflowCheckpointStore checkpoints, Authority authority) {
    public WorkflowExecution {
        Objects.requireNonNull(request); Objects.requireNonNull(policy);
        Objects.requireNonNull(checkpoints); Objects.requireNonNull(authority);
    }

    /** Open around the whole host/gateway invocation, including final output binding. */
    public WorkflowModelCallScope openModelScope(Clock clock) {
        var inherited = WorkflowModelCallScope.current();
        if (inherited.isPresent()) return WorkflowModelCallScope.open(inherited.orElseThrow());
        authority.verify(request); checkpoints.verifyLease(request.workId());
        var checkpoint = checkpoints.find(request.workId()).orElse(null);
        if (checkpoint == null) {
            Instant ceiling = clock.instant().plus(policy.timeout());
            Instant deadline = request.deadline().isBefore(ceiling) ? request.deadline() : ceiling;
            checkpoint = new WorkflowCheckpoint(request.workId(), request.requestFingerprint(), request.authorityFingerprint(),
                request.definitionFingerprint(), deadline, 0, WorkflowAllowance.Usage.empty(), List.of(),
                WorkflowCheckpoint.State.RUNNING, null, List.of(), null);
            if (!checkpoints.create(checkpoint)) checkpoint = checkpoints.find(request.workId()).orElseThrow();
        }
        if (!checkpoint.requestFingerprint().equals(request.requestFingerprint())
            || !checkpoint.authorityFingerprint().equals(request.authorityFingerprint())
            || !checkpoint.definitionFingerprint().equals(request.definitionFingerprint()))
            throw new IllegalArgumentException("WORKFLOW_REPLAY_CONFLICT");
        var allowance = new WorkflowAllowance(policy, checkpoint.deadline(), clock, checkpoint.usage(), usage -> {
            authority.verify(request); checkpoints.verifyLease(request.workId());
            for (int attempt = 0; attempt < 3; attempt++) {
                var old = checkpoints.find(request.workId()).orElseThrow();
                var next = new WorkflowCheckpoint(old.workId(), old.requestFingerprint(), old.authorityFingerprint(),
                    old.definitionFingerprint(), old.deadline(), old.version() + 1, usage, old.steps(), old.state(),
                    old.message(), old.questions(), old.stopCode());
                if (checkpoints.compareAndSet(old, next)) return;
            }
            throw new IllegalStateException("WORKFLOW_ALLOWANCE_CHECKPOINT_CONFLICT");
        });
        return WorkflowModelCallScope.open(allowance);
    }
    public interface Authority {
        /** Set only after the normal pending-proposal lifecycle has accepted the caller's confirmation. */
        default boolean resumeConfirmedReceipt() { return false; }
        void verify(BoundedWorkflowRunner.Request request);
        String fingerprint(Map<String, Object> parameters);
        BoundedWorkflowRunner.Outcome reconcile(WorkflowCheckpoint.Step step);
        /** Validate an explicitly declared execution outcome using trusted receipts only. */
        default BoundedWorkflowRunner.CompletionFinding validateCompletion(WorkflowCheckpoint checkpoint) { return null; }
        /** Apply the existing approved action evidence/secret projection, not raw transport output. */
        Map<String, Object> observation(AIActionMetaData metadata, ActionContext context, GovernedActionInvocationOutcome outcome);
        /** Typed application findings only; never classify error prose or user intent. */
        default boolean hasCorrectableValidationFindings(AIActionMetaData metadata, ActionContext context,
            GovernedActionInvocationOutcome outcome) { return false; }
        /** Receiver-proven noncommitted validation failure, not a transport or permission error. */
        default boolean isCorrectableNoncommittedFailure(AIActionMetaData metadata, ActionContext context,
            GovernedActionInvocationOutcome outcome) { return false; }
    }
}
