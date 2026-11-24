package ai.fabric.intent.orchestration.workflow;

import java.util.Objects;

/** Call-count propagation only. Provider admission and financial accounting remain independent. */
public final class WorkflowModelCallScope implements AutoCloseable {
    private static final ThreadLocal<WorkflowAllowance> CURRENT = new ThreadLocal<>();
    private final WorkflowAllowance previous;
    private final Thread owner = Thread.currentThread();
    private boolean closed;
    private WorkflowModelCallScope(WorkflowAllowance allowance) {
        previous = CURRENT.get();
        if (previous != null && previous != allowance)
            throw new IllegalStateException("A child workflow cannot replace its root allowance");
        CURRENT.set(Objects.requireNonNull(allowance));
    }
    public static WorkflowModelCallScope open(WorkflowAllowance allowance) { return new WorkflowModelCallScope(allowance); }
    public static java.util.Optional<WorkflowAllowance> current() { return java.util.Optional.ofNullable(CURRENT.get()); }
    public static void beforeModelCall() {
        WorkflowAllowance allowance = CURRENT.get();
        if (allowance != null) allowance.modelCall();
    }
    public static Runnable propagate(Runnable task) {
        WorkflowAllowance captured = CURRENT.get();
        if (captured == null) return task;
        return () -> { try (var ignored = open(captured)) { task.run(); } };
    }
    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Workflow scope belongs to another thread");
        if (!closed) {
            closed = true;
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        }
    }
}
