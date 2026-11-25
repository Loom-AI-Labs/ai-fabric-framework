package ai.fabric.intent.orchestration.workflow;

import java.util.Optional;

/**
 * Protected execution state with atomic compare-and-set. Durable hosts require a
 * durable implementation; active-request hosts may explicitly use a local journal,
 * never as a fallback for failed durable storage.
 */
public interface WorkflowCheckpointStore {
    /** Verify the host execution's lease or active-turn guard before dispatch. */
    void verifyLease(String workId);
    Optional<WorkflowCheckpoint> find(String workId);
    boolean create(WorkflowCheckpoint initial);
    boolean compareAndSet(WorkflowCheckpoint expected, WorkflowCheckpoint updated);
    /** Receipt-only settlement after a user control fence; never admits or dispatches another operation. */
    default boolean recordFencedOutcome(String workId, WorkflowCheckpoint.Step observed) { return false; }
}
