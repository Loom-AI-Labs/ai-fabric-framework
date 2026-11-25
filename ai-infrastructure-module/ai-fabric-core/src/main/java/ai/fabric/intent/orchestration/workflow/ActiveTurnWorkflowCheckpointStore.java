package ai.fabric.intent.orchestration.workflow;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Explicit request-local journal. Never used to replace a required durable store. */
public final class ActiveTurnWorkflowCheckpointStore implements WorkflowCheckpointStore {
    private final String workId;
    private final Runnable verify;
    private final AtomicReference<WorkflowCheckpoint> checkpoint = new AtomicReference<>();
    public ActiveTurnWorkflowCheckpointStore(String workId, Runnable verify) {
        this.workId = java.util.Objects.requireNonNull(workId);
        this.verify = java.util.Objects.requireNonNull(verify);
    }
    public void verifyLease(String id) {
        if (!workId.equals(id)) throw new SecurityException("WORKFLOW_TURN_MISMATCH");
        verify.run();
    }
    public Optional<WorkflowCheckpoint> find(String id) {
        if (!workId.equals(id)) throw new SecurityException("WORKFLOW_TURN_MISMATCH");
        return Optional.ofNullable(checkpoint.get());
    }
    public boolean create(WorkflowCheckpoint value) { verifyLease(value.workId()); return checkpoint.compareAndSet(null, value); }
    public boolean compareAndSet(WorkflowCheckpoint expected, WorkflowCheckpoint updated) {
        verifyLease(updated.workId()); return checkpoint.compareAndSet(expected, updated);
    }
    public boolean recordFencedOutcome(String id, WorkflowCheckpoint.Step observed) {
        if (!workId.equals(id)) throw new SecurityException("WORKFLOW_TURN_MISMATCH");
        var old = checkpoint.get();
        if (old == null || old.steps().isEmpty()) return false;
        var pending = old.steps().getLast();
        if (!pending.operationId().equals(observed.operationId()) || !pending.action().equals(observed.action())
            || pending.write() != observed.write() || !pending.parameterFingerprint().equals(observed.parameterFingerprint())
            || !pending.parameters().equals(observed.parameters())
            || pending.effect() != WorkflowCheckpoint.Effect.DISPATCHING) return false;
        var steps = new java.util.ArrayList<>(old.steps()); steps.set(steps.size() - 1, observed);
        return checkpoint.compareAndSet(old, new WorkflowCheckpoint(old.workId(), old.requestFingerprint(), old.authorityFingerprint(),
            old.definitionFingerprint(), old.deadline(), old.version() + 1, old.usage(), steps, WorkflowCheckpoint.State.STOPPED,
            old.message(), old.questions(), "WORKFLOW_AUTHORITY_FENCED", old.goal()));
    }
}
