package ai.fabric.execution.state;

import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint;
import ai.fabric.intent.orchestration.workflow.WorkflowCheckpointStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Checkpoints on an existing leased execution; never schedules or creates another job. */
public final class DurableWorkflowCheckpointStore implements WorkflowCheckpointStore {
    private final DurableExecutionRepository repository;
    private final DurableExecutionSecurity security;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String invocationId;
    private final String leaseOwner;
    private final String authorityFingerprint;
    private final String definitionFingerprint;

    public DurableWorkflowCheckpointStore(DurableExecutionRepository repository, DurableExecutionSecurity security,
        ObjectMapper mapper, Clock clock, DurableExecutionRecord leased) {
        this.repository = Objects.requireNonNull(repository); this.security = Objects.requireNonNull(security);
        this.mapper = Objects.requireNonNull(mapper).copy(); this.clock = Objects.requireNonNull(clock);
        invocationId = leased.invocationId(); leaseOwner = Objects.requireNonNull(leased.leaseOwner());
        authorityFingerprint = leased.accessFingerprint(); definitionFingerprint = leased.specialistContentHash();
        verifyLease(invocationId);
    }
    @Override public void verifyLease(String workId) { current(workId); }
    @Override public Optional<WorkflowCheckpoint> find(String workId) { return decode(current(workId)); }
    @Override public boolean create(WorkflowCheckpoint initial) {
        var record = current(initial.workId());
        if (record.protectedWorkflowCheckpoint() != null) return false;
        validate(initial, record);
        return repository.compareAndSet(record, record.withWorkflowCheckpoint(encode(initial), clock.instant()));
    }
    @Override public boolean compareAndSet(WorkflowCheckpoint expected, WorkflowCheckpoint updated) {
        var record = current(updated.workId());
        var actual = decode(record).orElse(null);
        if (actual == null || !security.sameFingerprint(security.canonicalHash(actual), security.canonicalHash(expected))) return false;
        if (updated.version() != expected.version() + 1 || !updated.requestFingerprint().equals(expected.requestFingerprint())
            || !updated.deadline().equals(expected.deadline())) throw new IllegalArgumentException("Invalid workflow checkpoint transition");
        validate(updated, record);
        return repository.compareAndSet(record, record.withWorkflowCheckpoint(encode(updated), clock.instant()));
    }
    private DurableExecutionRecord current(String workId) {
        if (!invocationId.equals(workId)) throw new IllegalArgumentException("WORKFLOW_EXECUTION_MISMATCH");
        var record = repository.findById(invocationId).orElseThrow(() -> new IllegalStateException("WORKFLOW_EXECUTION_MISSING"));
        if (record.status() != ExecutionHandleStatus.RUNNING || !leaseOwner.equals(record.leaseOwner())
            || record.leaseUntil() == null || !clock.instant().isBefore(record.leaseUntil())
            || !security.sameFingerprint(authorityFingerprint, record.accessFingerprint())
            || !security.sameFingerprint(definitionFingerprint, record.specialistContentHash()))
            throw new IllegalStateException("WORKFLOW_EXECUTION_LEASE_LOST");
        return record;
    }
    private void validate(WorkflowCheckpoint checkpoint, DurableExecutionRecord record) {
        if (!security.sameFingerprint(checkpoint.authorityFingerprint(), authorityFingerprint)
            || !security.sameFingerprint(checkpoint.definitionFingerprint(), definitionFingerprint)
            || !security.sameFingerprint(checkpoint.requestFingerprint(), record.requestFingerprint())
            || checkpoint.deadline().isAfter(record.deadline()))
            throw new IllegalArgumentException("WORKFLOW_EXECUTION_SCOPE_MISMATCH");
    }
    private String encode(WorkflowCheckpoint checkpoint) {
        return security.protect(Map.of("checkpoint", mapper.valueToTree(checkpoint)), binding());
    }
    private Optional<WorkflowCheckpoint> decode(DurableExecutionRecord record) {
        if (record.protectedWorkflowCheckpoint() == null) return Optional.empty();
        var payload = security.unprotect(record.protectedWorkflowCheckpoint(), binding());
        var checkpoint = mapper.convertValue(payload.get("checkpoint"), WorkflowCheckpoint.class);
        validate(checkpoint, record);
        return Optional.of(checkpoint);
    }
    private String binding() { return "workflow-checkpoint:" + invocationId; }
}
