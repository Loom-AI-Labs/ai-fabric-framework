package ai.fabric.intent.orchestration.workflow;

import java.util.Optional;

/** Implement with protected durable state and atomic compare-and-set; no in-memory write fallback. */
public interface WorkflowCheckpointStore {
    /** Verify the host execution's durable lease; concurrent workers must not dispatch. */
    void verifyLease(String workId);
    Optional<WorkflowCheckpoint> find(String workId);
    boolean create(WorkflowCheckpoint initial);
    boolean compareAndSet(WorkflowCheckpoint expected, WorkflowCheckpoint updated);
}
