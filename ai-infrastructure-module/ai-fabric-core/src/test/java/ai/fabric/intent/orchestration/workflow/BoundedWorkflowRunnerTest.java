package ai.fabric.intent.orchestration.workflow;

import static org.assertj.core.api.Assertions.*;
import ai.fabric.intent.orchestration.workflow.BoundedWorkflowRunner.*;
import ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BoundedWorkflowRunnerTest {
    static final Instant NOW = Instant.parse("2026-11-24T08:00:00Z");
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    final Store store = new Store();
    final Fixture actions = new Fixture();
    final BoundedWorkflowRunner runner = new BoundedWorkflowRunner(store, clock);
    WorkflowPolicy policy(int repairs) { return new WorkflowPolicy(true, 8, 3, 16, repairs, Duration.ofSeconds(120)); }
    Request request(boolean preview) { return new Request("work", "request", "authority", "definition", NOW.plusSeconds(120), preview); }
    Decision action(String name, int value) { return new Decision(DecisionType.ACTION, name, Map.of("value", value), null, List.of()); }
    Decision done() { return new Decision(DecisionType.COMPLETE, null, null, "Finished", null); }
    Planner sequence(Decision... decisions) {
        var queue = new ArrayDeque<>(List.of(decisions));
        return (checkpoint, allowance) -> { WorkflowModelCallScope.beforeModelCall(); return queue.remove(); };
    }
    @Test void readsWritesThenReadsFreshStateAndMakesCorrectiveWrite() {
        var result = runner.run(request(false), policy(2), sequence(action("read", 0), action("write", 4),
            action("read", 0), action("write", 5), done()), actions);
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.steps()).hasSize(4);
        assertThat(result.steps().get(0).observation()).containsEntry("value", 0);
        assertThat(result.steps().get(2).observation()).containsEntry("value", 4);
        assertThat(actions.value).isEqualTo(5);
        assertThat(actions.dispatched).containsExactly("work:step:1", "work:step:2", "work:step:3", "work:step:4");
        assertThat(result.usage()).isEqualTo(new WorkflowAllowance.Usage(4, 2, 5, 0));
    }
    @Test void completedReplayDoesNotCallPlannerOrWriteAgain() {
        var result = runner.run(request(false), policy(2), sequence(action("write", 2), done()), actions);
        assertThat(runner.run(request(false), policy(2), (c, a) -> { throw new AssertionError(); }, actions)).isEqualTo(result);
        assertThat(actions.dispatched).hasSize(1);
    }
    @Test void anUnmetExplicitCompletionContractReturnsFeedbackAndLetsTheModelChooseTheWrite() {
        var guarded = new Fixture() {
            @Override public CompletionFinding validateCompletion(WorkflowCheckpoint checkpoint) {
                return value == 0 ? new CompletionFinding("DRAFT_EFFECT_MISSING", "The checked candidate has no matching saved receipt.") : null;
            }
        };
        var replies = new ArrayDeque<>(List.of(done(), action("write", 2), done()));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var result = runner.run(request(false), policy(1), (checkpoint, allowance) -> {
            WorkflowModelCallScope.beforeModelCall();
            if (calls.incrementAndGet() == 2) {
                assertThat(checkpoint.stopCode()).isEqualTo("DRAFT_EFFECT_MISSING");
                assertThat(checkpoint.message()).contains("matching saved receipt");
            }
            return replies.remove();
        }, guarded);
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.usage()).isEqualTo(new WorkflowAllowance.Usage(1, 1, 3, 1));
        assertThat(guarded.dispatched).hasSize(1);
    }
    @Test void completionFeedbackCannotForceAWrittenEffectOrOverrideNeedsInput() {
        var guarded = new Fixture() {
            @Override public CompletionFinding validateCompletion(WorkflowCheckpoint checkpoint) {
                return new CompletionFinding("DRAFT_EFFECT_MISSING", "A checked draft is not saved.");
            }
        };
        var result = runner.run(request(false), policy(1), sequence(done(),
            new Decision(DecisionType.NEEDS_INPUT, null, null, "Which connection?", List.of("Connection reference?"))), guarded);
        assertThat(result.state()).isEqualTo(State.NEEDS_INPUT);
        assertThat(guarded.dispatched).isEmpty();
        assertThat(result.usage().corrections()).isEqualTo(1);
    }
    @Test void completionFindingsRespectTheSharedCorrectionLimitAndRemainVisible() {
        var guarded = new Fixture() {
            @Override public CompletionFinding validateCompletion(WorkflowCheckpoint checkpoint) {
                return new CompletionFinding("DRAFT_EFFECT_MISSING", "A checked draft is not saved.");
            }
        };
        var result = runner.run(request(false), policy(0), sequence(done()), guarded);
        assertThat(result.state()).isEqualTo(State.STOPPED);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_CORRECTION_LIMIT");
        assertThat(result.message()).isEqualTo("A checked draft is not saved.");
        assertThat(result.usage().modelCalls()).isEqualTo(1);
        assertThat(guarded.dispatched).isEmpty();
    }
    @Test void differentPayloadUnderSameWorkIdConflictsBeforeInference() {
        runner.run(request(false), policy(2), sequence(done()), actions);
        var other = new Request("work", "other", "authority", "definition", NOW.plusSeconds(120), false);
        assertThatThrownBy(() -> runner.run(other, policy(2), (c, a) -> { throw new AssertionError(); }, actions))
            .hasMessage("WORKFLOW_REPLAY_CONFLICT");
    }
    @Test void disabledPolicyNeverAcquiresLeaseOrCallsAnything() {
        store.authorized = false;
        assertThatThrownBy(() -> runner.run(request(false), WorkflowPolicy.disabled(), sequence(done()), actions))
            .hasMessage("WORKFLOW_DISABLED");
        assertThat(store.checkpoint).isNull();
    }
    @Test void previewRejectsWriteBeforeDispatch() {
        var result = runner.run(request(true), policy(2), sequence(action("write", 2)), actions);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_PREVIEW_WRITE_DENIED");
        assertThat(actions.dispatched).isEmpty();
        assertThat(result.usage().writes()).isZero();
    }
    @ParameterizedTest @ValueSource(ints = {0, 2})
    void needsInputAlwaysStopsRegardlessOfCorrectionPolicy(int corrections) {
        var result = runner.run(request(false), policy(corrections), sequence(new Decision(DecisionType.NEEDS_INPUT,
            null, Map.of("unusedPartialProposal", true), "Which connection?", List.of("Connection reference?"))), actions);
        assertThat(result.state()).isEqualTo(State.NEEDS_INPUT);
        assertThat(result.questions()).containsExactly("Connection reference?");
        assertThat(actions.dispatched).isEmpty();
    }
    @Test void typedValidationCanBeCorrectedButDoesNotResetBudgets() {
        actions.rejectFirst = true;
        var result = runner.run(request(false), policy(1), sequence(action("write", 1), action("write", 2), done()), actions);
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.usage()).isEqualTo(new WorkflowAllowance.Usage(2, 2, 3, 1));
    }
    @Test void disabledCorrectionsDoNotRetryRejectedCandidate() {
        actions.rejectFirst = true;
        var result = runner.run(request(false), policy(0), sequence(action("write", 1)), actions);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_CORRECTION_LIMIT");
        assertThat(result.steps().getFirst().effect()).isEqualTo(Effect.REJECTED);
        assertThat(actions.dispatched).hasSize(1);
    }
    @Test void failedFinalNarrationKeepsSuccessfulWrite() {
        var result = runner.run(request(false), policy(2), sequence(action("write", 7)), actions);
        assertThat(result.state()).isEqualTo(State.STOPPED);
        assertThat(result.message()).isNull();
        assertThat(result.steps().getFirst().effect()).isEqualTo(Effect.EXECUTED);
        assertThat(actions.value).isEqualTo(7);
    }
    @Test void confirmationPausesBeforeAnotherPlannerCall() {
        actions.confirmation = true;
        var result = runner.run(request(false), policy(2), sequence(action("write", 7)), actions);
        assertThat(result.state()).isEqualTo(State.CONFIRMATION_REQUIRED);
        assertThat(result.usage().modelCalls()).isEqualTo(1);
        assertThat(actions.value).isZero();
    }
    @Test void confirmedReceiptContinuesWithoutRepeatingTheWriteOrResettingUsage() {
        actions.confirmation = true;
        runner.run(request(false), policy(2), sequence(action("write", 7)), actions);
        // The ordinary governed confirmation receiver has executed this operation.
        actions.value = 7; actions.confirmation = false;
        var result = runner.resumeConfirmed(request(false), policy(2), sequence(action("read", 0), done()), actions);
        assertThat(result.state()).as("checkpoint %s steps %s", result, result.steps()).isEqualTo(State.COMPLETE);
        assertThat(result.usage()).isEqualTo(new WorkflowAllowance.Usage(2, 1, 3, 0));
        assertThat(result.steps().getFirst().effect()).isEqualTo(Effect.EXECUTED);
        assertThat(actions.dispatched).containsExactly("work:step:1", "work:step:2");
        assertThat(actions.reconciled).isEqualTo(1);
    }
    @Test void resumingWithoutAnExecutedConfirmationReceiptDoesNotCallPlannerOrWriter() {
        actions.confirmation = true;
        runner.run(request(false), policy(2), sequence(action("write", 7)), actions);
        var result = runner.resumeConfirmed(request(false), policy(2), (c, a) -> { throw new AssertionError(); }, new Fixture() {
            @Override public Outcome reconcile(Step step) { return new Outcome(Effect.CONFIRMATION_REQUIRED, Map.of(), null, false); }
        });
        assertThat(result.state()).isEqualTo(State.CONFIRMATION_REQUIRED);
        assertThat(result.usage().modelCalls()).isEqualTo(1);
        assertThat(actions.dispatched).hasSize(1);
    }
    @Test void unknownOutcomeDoesNotReplayWrite() {
        actions.loseReply = true;
        var result = runner.run(request(false), policy(2), sequence(action("write", 7)), actions);
        assertThat(result.steps().getFirst().effect()).isEqualTo(Effect.OUTCOME_UNKNOWN);
        assertThat(actions.value).isEqualTo(7);
        runner.run(request(false), policy(2), (c, a) -> { throw new AssertionError(); }, actions);
        assertThat(actions.dispatched).hasSize(1);
    }
    @Test void crashAfterReservationReconcilesWithoutDispatch() {
        var step = new Step("work:step:1", "write", true, Map.of("value", 7), "fingerprint",
            Effect.DISPATCHING, Map.of(), null);
        store.checkpoint = new WorkflowCheckpoint("work", "request", "authority", "definition", NOW.plusSeconds(120), 4,
            new WorkflowAllowance.Usage(1, 1, 1, 0), List.of(step), State.RUNNING, null, List.of(), null);
        actions.value = 7;
        var result = runner.run(request(false), policy(2), sequence(done()), actions);
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.steps().getFirst().effect()).isEqualTo(Effect.EXECUTED);
        assertThat(actions.dispatched).isEmpty();
        assertThat(actions.reconciled).isEqualTo(1);
    }
    @Test void cancellationAfterWriteKeepsReceiptAndPreventsNextAction() {
        actions.cancelAfter = true;
        var result = runner.run(request(false), policy(2), sequence(action("write", 7), action("write", 8)), actions);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_CANCELLED");
        assertThat(result.steps().getFirst().effect()).isEqualTo(Effect.EXECUTED);
        assertThat(actions.value).isEqualTo(7);
    }
    @Test void modelBudgetAdmissionIsPersistedBeforeProviderCall() {
        var small = new WorkflowPolicy(true, 8, 3, 1, 2, Duration.ofSeconds(120));
        var result = runner.run(request(false), small, (c, a) -> {
            WorkflowModelCallScope.beforeModelCall();
            assertThat(store.checkpoint.usage().modelCalls()).isEqualTo(1);
            WorkflowModelCallScope.beforeModelCall();
            throw new AssertionError();
        }, actions);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_MODEL_LIMIT");
        assertThat(result.usage().modelCalls()).isEqualTo(1);
    }
    @Test void unknownActionStopsWithoutDispatch() {
        var result = runner.run(request(false), policy(2), sequence(action("unregistered", 1)), actions);
        assertThat(result.state()).isEqualTo(State.STOPPED);
        assertThat(actions.dispatched).isEmpty();
    }
    @Test void incompleteModelDecisionUsesTheSameBoundedCorrectionAndModelAllowance() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var result = runner.run(request(false), policy(1), (checkpoint, allowance) -> {
            WorkflowModelCallScope.beforeModelCall();
            if (calls.incrementAndGet() == 1) throw new PlanningFinding();
            assertThat(checkpoint.stopCode()).isEqualTo("WORKFLOW_DECISION_INVALID");
            return done();
        }, actions);
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.usage()).isEqualTo(new WorkflowAllowance.Usage(0, 0, 2, 1));
    }
    @Test void invalidModelDecisionDoesNotRepairWhenCorrectionsAreDisabled() {
        var result = runner.run(request(false), policy(0), (checkpoint, allowance) -> {
            WorkflowModelCallScope.beforeModelCall(); throw new PlanningFinding();
        }, actions);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_CORRECTION_LIMIT");
        assertThat(result.usage().modelCalls()).isEqualTo(1);
    }
    @Test void revokedActorIsDeniedBeforeReplayOrModelCall() {
        actions.authorized = false;
        assertThatThrownBy(() -> runner.run(request(false), policy(2), sequence(done()), actions)).hasMessage("DENIED");
        assertThat(store.checkpoint).isNull();
    }
    @Test void staleLeaseIsDeniedBeforeReplayOrModelCall() {
        store.authorized = false;
        assertThatThrownBy(() -> runner.run(request(false), policy(2), sequence(done()), actions)).hasMessage("LEASE_LOST");
    }
    @Test void childrenCannotReplaceRootAllowance() {
        var parent = new WorkflowAllowance(policy(2), NOW.plusSeconds(60), clock, WorkflowAllowance.Usage.empty());
        var child = new WorkflowAllowance(policy(2), NOW.plusSeconds(60), clock, WorkflowAllowance.Usage.empty());
        try (var ignored = WorkflowModelCallScope.open(parent)) {
            assertThatThrownBy(() -> WorkflowModelCallScope.open(child)).isInstanceOf(IllegalStateException.class);
            try (var nested = WorkflowModelCallScope.open(parent)) { WorkflowModelCallScope.beforeModelCall(); }
            assertThat(parent.usage().modelCalls()).isEqualTo(1);
        }
    }

    @Test void wholeGatewayScopeCountsCallsBeforePlannerAndAfterItsResultWithoutReset() {
        var bounded = new WorkflowPolicy(true, 8, 3, 4, 2, Duration.ofSeconds(120));
        var execution = new WorkflowExecution(request(false), bounded, store, new WorkflowExecution.Authority() {
            public void verify(Request request) { actions.verifyAuthority(request); }
            public String fingerprint(Map<String, Object> values) { return "fingerprint"; }
            public Outcome reconcile(Step step) { return actions.reconcile(step); }
            public Map<String, Object> observation(ai.fabric.intent.action.AIActionMetaData metadata,
                ai.fabric.intent.action.ActionContext context, ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome outcome) { return Map.of(); }
        });
        try (var ignored = execution.openModelScope(clock)) {
            WorkflowModelCallScope.beforeModelCall();
            assertThat(store.checkpoint.usage().modelCalls()).isEqualTo(1);
            var result = runner.run(request(false), bounded, sequence(action("write", 7), done()), actions);
            assertThat(result.usage().modelCalls()).isEqualTo(3);
            WorkflowModelCallScope.beforeModelCall();
            assertThat(store.checkpoint.usage().modelCalls()).isEqualTo(4);
            assertThatThrownBy(WorkflowModelCallScope::beforeModelCall).hasMessage("WORKFLOW_MODEL_LIMIT");
            assertThat(actions.value).isEqualTo(7);
        }
        assertThat(WorkflowModelCallScope.current()).isEmpty();
    }
    @Test void nullableStructuredValuesAreImmutableAndDoNotLoseExplicitNulls() {
        var nested = new java.util.LinkedHashMap<String, Object>(); nested.put("optional", null);
        var params = new java.util.LinkedHashMap<String, Object>(); params.put("nested", nested);
        var decision = new Decision(DecisionType.ACTION, "read", params, null, List.of());
        nested.put("optional", "changed later");
        var result = runner.run(request(false), policy(2), sequence(decision, done()), actions);
        assertThat((Map<String, Object>) result.steps().getFirst().parameters().get("nested")).containsEntry("optional", null);
        assertThatThrownBy(() -> ((Map<String, Object>) result.steps().getFirst().parameters().get("nested")).put("new", 1))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    static class Store implements WorkflowCheckpointStore {
        WorkflowCheckpoint checkpoint; boolean authorized = true;
        public void verifyLease(String workId) { if (!authorized) throw new IllegalStateException("LEASE_LOST"); }
        public Optional<WorkflowCheckpoint> find(String workId) { return Optional.ofNullable(checkpoint); }
        public boolean create(WorkflowCheckpoint next) { if (checkpoint != null) return false; checkpoint = next; return true; }
        public boolean compareAndSet(WorkflowCheckpoint expected, WorkflowCheckpoint next) {
            if (checkpoint != expected) return false; checkpoint = next; return true;
        }
    }
    static class Fixture implements Actions {
        boolean authorized = true, rejectFirst, confirmation, loseReply, cancelAfter;
        int value, reconciled;
        final List<String> dispatched = new ArrayList<>();
        public void verifyAuthority(Request request) { if (!authorized) throw new IllegalStateException("DENIED"); }
        public boolean isWrite(String action) {
            if (!List.of("read", "write").contains(action)) throw new IllegalStateException("ACTION_DENIED");
            return action.equals("write");
        }
        public String fingerprint(Map<String, Object> params) { return "fingerprint"; }
        public Outcome invoke(Step step, WorkflowAllowance allowance) {
            dispatched.add(step.operationId());
            if (rejectFirst && dispatched.size() == 1) return new Outcome(Effect.REJECTED, Map.of("field", "value"), "INVALID_PARAMETER", true);
            if (confirmation && step.write()) return new Outcome(Effect.CONFIRMATION_REQUIRED, Map.of(), "CONFIRMATION_REQUIRED", false);
            if (step.write()) value = (int) step.parameters().get("value");
            if (loseReply) throw new IllegalStateException("Lost reply after commit");
            if (cancelAfter) allowance.cancel();
            return new Outcome(Effect.EXECUTED, Map.of("value", value), null, false);
        }
        public Outcome reconcile(Step step) { reconciled++; return new Outcome(Effect.EXECUTED, Map.of("value", value), null, false); }
    }
}
