package ai.fabric.intent.orchestration.workflow;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** One request-wide allowance. Child work must inherit this instance, not create another. */
public final class WorkflowAllowance {
    public record Usage(int actions, int writes, int modelCalls, int corrections) {
        public Usage {
            if (actions < 0 || writes < 0 || modelCalls < 0 || corrections < 0 || writes > actions)
                throw new IllegalArgumentException("Invalid workflow usage");
        }
        public static Usage empty() { return new Usage(0, 0, 0, 0); }
    }
    public static final class Stop extends RuntimeException {
        private final String code;
        public Stop(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }

    private final WorkflowPolicy policy;
    private final Instant deadline;
    private final Clock clock;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Usage usage;
    private final Consumer<Usage> admissionRecorder;

    public WorkflowAllowance(WorkflowPolicy policy, Instant deadline, Clock clock, Usage usage) {
        this(policy, deadline, clock, usage, ignored -> {});
    }
    public WorkflowAllowance(WorkflowPolicy policy, Instant deadline, Clock clock, Usage usage, Consumer<Usage> admissionRecorder) {
        this.policy = Objects.requireNonNull(policy);
        this.deadline = Objects.requireNonNull(deadline);
        this.clock = Objects.requireNonNull(clock);
        this.usage = Objects.requireNonNull(usage);
        this.admissionRecorder = Objects.requireNonNull(admissionRecorder);
        if (!policy.enabled()) throw new IllegalArgumentException("Workflow is disabled");
        if (usage.actions() > policy.maxActions() || usage.writes() > policy.maxWrites()
            || usage.modelCalls() > policy.maxModelCalls() || usage.corrections() > policy.maxCorrections())
            throw new IllegalArgumentException("Persisted usage exceeds workflow policy");
    }

    public void verify() {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new Stop("WORKFLOW_CANCELLED");
        if (!clock.instant().isBefore(deadline)) throw new Stop("WORKFLOW_DEADLINE_EXCEEDED");
    }
    public synchronized void modelCall() {
        verify();
        if (usage.modelCalls() >= policy.maxModelCalls()) throw new Stop("WORKFLOW_MODEL_LIMIT");
        admit(new Usage(usage.actions(), usage.writes(), usage.modelCalls() + 1, usage.corrections()));
    }
    public synchronized void action(boolean write) {
        verify();
        if (usage.actions() >= policy.maxActions()) throw new Stop("WORKFLOW_ACTION_LIMIT");
        if (write && usage.writes() >= policy.maxWrites()) throw new Stop("WORKFLOW_WRITE_LIMIT");
        admit(new Usage(usage.actions() + 1, usage.writes() + (write ? 1 : 0), usage.modelCalls(), usage.corrections()));
    }
    public synchronized void correction() {
        verify();
        if (usage.corrections() >= policy.maxCorrections()) throw new Stop("WORKFLOW_CORRECTION_LIMIT");
        admit(new Usage(usage.actions(), usage.writes(), usage.modelCalls(), usage.corrections() + 1));
    }
    private void admit(Usage next) { admissionRecorder.accept(next); usage = next; }
    public synchronized Usage usage() { return usage; }
    public Instant deadline() { return deadline; }
    public void cancel() { cancelled.set(true); }
}
