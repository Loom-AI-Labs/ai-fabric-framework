package ai.fabric.intent.orchestration.workflow;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ActiveTurnWorkflowCheckpointStoreTest {
    final AtomicBoolean fenced = new AtomicBoolean();
    final ActiveTurnWorkflowCheckpointStore store = new ActiveTurnWorkflowCheckpointStore("turn", () -> {
        if (fenced.get()) throw new SecurityException("TURN_CANCELLED");
    });
    WorkflowCheckpoint.Step step() {
        return new WorkflowCheckpoint.Step("turn:1", "write", true, Map.of("id", "own"), "params",
            WorkflowCheckpoint.Effect.DISPATCHING, Map.of(), null);
    }
    WorkflowCheckpoint checkpoint(long version, List<WorkflowCheckpoint.Step> steps) {
        return new WorkflowCheckpoint("turn", "request", "authority", "definition", Instant.now().plusSeconds(60), version,
            WorkflowAllowance.Usage.empty(), steps, WorkflowCheckpoint.State.RUNNING, null, List.of(), null);
    }
    @Test void compareAndSetRetainsExactJournalAndRejectsStaleState() {
        var first = checkpoint(0, List.of()); var next = checkpoint(1, List.of(step()));
        assertThat(store.create(first)).isTrue(); assertThat(store.create(first)).isFalse();
        assertThat(store.compareAndSet(first, next)).isTrue();
        assertThat(store.compareAndSet(first, checkpoint(2, List.of()))).isFalse();
        assertThat(store.find("turn")).contains(next);
    }
    @Test void foreignIdentifiersNeverReadOrDispatch() {
        assertThatThrownBy(() -> store.find("foreign")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.verifyLease("foreign")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.recordFencedOutcome("foreign", step())).isInstanceOf(SecurityException.class);
    }
    @Test void cancellationStopsFurtherChangesButExactExecutedReceiptCanSettle() {
        var pending = step(); var initial = checkpoint(0, List.of(pending)); store.create(initial);
        fenced.set(true);
        assertThatThrownBy(() -> store.verifyLease("turn")).isInstanceOf(SecurityException.class);
        assertThatThrownBy(() -> store.compareAndSet(initial, checkpoint(1, List.of()))).isInstanceOf(SecurityException.class);
        var receipt = pending.observed(WorkflowCheckpoint.Effect.EXECUTED, Map.of("receipt", "actual"), null);
        assertThat(store.recordFencedOutcome("turn", receipt)).isTrue();
        assertThat(store.find("turn").orElseThrow().steps()).containsExactly(receipt);
        assertThat(store.find("turn").orElseThrow().state()).isEqualTo(WorkflowCheckpoint.State.STOPPED);
        assertThat(store.recordFencedOutcome("turn", receipt)).isFalse();
    }
    @Test void receiptSettlementCannotChangeActionParametersOrClaimAnotherOperation() {
        store.create(checkpoint(0, List.of(step()))); fenced.set(true);
        var forged = new WorkflowCheckpoint.Step("turn:1", "write", true, Map.of("id", "foreign"), "params",
            WorkflowCheckpoint.Effect.EXECUTED, Map.of(), null);
        assertThat(store.recordFencedOutcome("turn", forged)).isFalse();
        assertThat(store.find("turn").orElseThrow().steps().getFirst().effect()).isEqualTo(WorkflowCheckpoint.Effect.DISPATCHING);
    }
}
