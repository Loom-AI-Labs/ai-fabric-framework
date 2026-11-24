package ai.fabric.intent.orchestration.workflow;

import static org.assertj.core.api.Assertions.*;
import ai.fabric.dto.ProviderOutcome;
import ai.fabric.intent.orchestration.control.OrchestrationControlException;
import ai.fabric.intent.orchestration.workflow.BoundedWorkflowRunner.*;
import ai.fabric.intent.orchestration.workflow.WorkflowCheckpoint.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class GoalWorkflowRunnerTest {
    final Instant now = BoundedWorkflowRunnerTest.NOW;
    final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    final BoundedWorkflowRunnerTest.Store store = new BoundedWorkflowRunnerTest.Store();
    final BoundedWorkflowRunner runner = new BoundedWorkflowRunner(store, clock);
    final Request request = new Request("work", "request", "authority", "definition", now.plusSeconds(3600), false);
    WorkflowPolicy policy() { return new WorkflowPolicy(true, 4, 3, 4, 2, Duration.ofSeconds(120),
        new WorkflowGoalPolicy(true, Duration.ofHours(1), Duration.ZERO, 8, 4, List.of("api@1"))); }
    Decision action(int value) { return new Decision(DecisionType.ACTION, "write", Map.of("value", value), null, null); }
    Decision done() { return new Decision(DecisionType.COMPLETE, null, null, "Saved", null); }
    BoundedWorkflowRunnerTest.Fixture actions() { return new BoundedWorkflowRunnerTest.Fixture() {
        public String fingerprint(Map<String, Object> parameters) { return parameters.toString(); }
    }; }

    @Test void continuesBeyondTwoCorrectionsThreeWritesAndSixteenCallsWithoutResettingUsageOrIds() {
        var actions = actions(); var calls = new AtomicInteger();
        Planner planner = (checkpoint, allowance) -> {
            WorkflowModelCallScope.beforeModelCall();
            if (calls.incrementAndGet() <= 5) throw new PlanningFinding();
            return checkpoint.steps().size() < 18 ? action(checkpoint.steps().size() + 1) : done();
        };
        WorkflowCheckpoint result = null;
        for (int segment = 0; segment < 30; segment++) {
            result = new BoundedWorkflowRunner(store, clock).run(request, policy(), planner, actions);
            if (result.state() != State.YIELDED) break;
        }
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.usage().writes()).isEqualTo(18);
        assertThat(result.usage().corrections()).isGreaterThan(2);
        assertThat(result.usage().modelCalls()).isGreaterThan(16);
        assertThat(result.goal().segmentNumber()).isGreaterThan(4);
        assertThat(result.steps()).extracting(Step::operationId).containsExactlyElementsOf(
            java.util.stream.IntStream.rangeClosed(1, 18).mapToObj(i -> "work:step:" + i).toList());
        assertThat(actions.dispatched).hasSize(18);
        assertThat(result.deadline()).isEqualTo(request.deadline());
    }

    @Test void repeatedEmptyPlannerPausesOperationallyAcrossSegmentsWithSafeDiagnostics() {
        var safe = new ProviderOutcome("workflow", "model", 8192, "req-1", "length", 40L, 90L, null, 0, "SUCCESS");
        Planner planner = (checkpoint, allowance) -> {
            WorkflowModelCallScope.beforeModelCall();
            throw new PlanningFinding("WORKFLOW_EMPTY_RESPONSE", "The provider returned empty content.", safe, true);
        };
        var first = runner.run(request, policy(), planner, actions());
        assertThat(first.state()).isEqualTo(State.YIELDED);
        var result = runner.run(request, policy(), planner, actions());
        assertThat(result.state()).isEqualTo(State.PAUSED);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_PLANNER_UNAVAILABLE");
        assertThat(result.goal().recentFailures()).hasSize(4);
        assertThat(result.goal().recentFailures().getLast().providerOutcome()).isEqualTo(safe);
        assertThat(runner.run(request, policy(), (c, a) -> { throw new AssertionError(); }, actions())).isEqualTo(result);
    }

    @Test void unknownWriteReconcilesOriginalOperationOnExplicitResumeAndNeverReplays() {
        var actions = actions(); actions.loseReply = true;
        var first = runner.run(request, policy(), (c, a) -> { a.modelCall(); return action(7); }, actions);
        assertThat(first.state()).isEqualTo(State.PAUSED);
        assertThat(first.steps().getFirst().effect()).isEqualTo(Effect.OUTCOME_UNKNOWN);
        actions.loseReply = false;
        var resumed = runner.resumeGoal(request, policy(), (c, a) -> { a.modelCall(); return done(); }, actions);
        assertThat(resumed.state()).isEqualTo(State.COMPLETE);
        assertThat(resumed.steps().getFirst().effect()).isEqualTo(Effect.EXECUTED);
        assertThat(actions.dispatched).containsExactly("work:step:1");
        assertThat(actions.reconciled).isEqualTo(1);
        assertThat(resumed.usage().writes()).isEqualTo(1);
    }

    @Test void technicalProfilePrefixOnAnActionNeverBypassesReceiptReconciliation() {
        var pending = new Step("work:step:1", "profile:write", true, Map.of("value", 7), "hash", Effect.DISPATCHING, Map.of(), null);
        var progress = new WorkflowGoalProgress(1, now, now.plusSeconds(120), WorkflowAllowance.Usage.empty(), null, 0, 0, null, List.of());
        store.checkpoint = new WorkflowCheckpoint("work", "request", "authority", "definition", request.deadline(), 1,
            new WorkflowAllowance.Usage(1, 1, 1, 0), List.of(pending), State.RUNNING, null, List.of(), null, progress);
        var actions = actions();
        var result = runner.run(request, policy(), (c, a) -> { a.modelCall(); return done(); }, actions);
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.steps().getFirst().effect()).isEqualTo(Effect.EXECUTED);
        assertThat(actions.reconciled).isEqualTo(1);
        assertThat(actions.dispatched).isEmpty();
    }

    @Test void noProgressUsesExactExecutionIdentitiesAndPausesInsteadOfSpinning() {
        var small = new WorkflowPolicy(true, 4, 3, 4, 2, Duration.ofSeconds(120),
            new WorkflowGoalPolicy(true, Duration.ofHours(1), Duration.ZERO, 3, 4, List.of()));
        var actions = actions();
        Planner planner = (c, a) -> { a.modelCall(); return action(1); };
        WorkflowCheckpoint result = null;
        for (int i = 0; i < 8; i++) {
            result = runner.run(request, small, planner, actions);
            if (result.state() != State.YIELDED) break;
        }
        assertThat(result.state()).isEqualTo(State.PAUSED);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_NO_PROGRESS");
        assertThat(result.usage().writes()).isEqualTo(4);
    }

    @Test void existingNoOpCompletionNeedsNoNewWrite() {
        var result = runner.run(request, policy(), (c, a) -> { a.modelCall(); return done(); }, actions());
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.steps()).isEmpty();
    }

    @Test void authenticatedDirectiveResumeReopensNeedsInputWithoutChangingRootIdentityOrReceipts() {
        var actions = actions(); var count = new AtomicInteger();
        var waiting = runner.run(request, policy(), (c, a) -> {
            a.modelCall(); return count.incrementAndGet() == 1 ? action(7)
                : new Decision(DecisionType.NEEDS_INPUT, null, null, "Which owned connection?", List.of("Connection reference?"));
        }, actions);
        assertThat(waiting.state()).isEqualTo(State.NEEDS_INPUT);
        assertThat(runner.run(request, policy(), (c, a) -> { throw new AssertionError(); }, actions)).isEqualTo(waiting);
        var resumed = runner.resumeGoal(request, policy(), (c, a) -> {
            a.modelCall(); assertThat(c.questions()).containsExactly("Connection reference?"); return done();
        }, actions);
        assertThat(resumed.state()).isEqualTo(State.COMPLETE);
        assertThat(resumed.requestFingerprint()).isEqualTo(waiting.requestFingerprint());
        assertThat(resumed.usage()).isEqualTo(new WorkflowAllowance.Usage(1, 1, 3, 0));
        assertThat(resumed.goal().segmentNumber()).isEqualTo(2);
        assertThat(resumed.steps()).isEqualTo(waiting.steps());
        assertThat(actions.dispatched).containsExactly("work:step:1");
    }

    @Test void rootLifetimeExpiryPausesWithoutResettingUsage() {
        runner.run(request, policy(), (c, a) -> { a.modelCall(); throw new PlanningFinding(); }, actions());
        var expired = new BoundedWorkflowRunner(store, Clock.fixed(now.plusSeconds(3601), ZoneOffset.UTC))
            .run(request, policy(), (c, a) -> { throw new AssertionError(); }, actions());
        assertThat(expired.state()).isEqualTo(State.PAUSED);
        assertThat(expired.usage()).isEqualTo(store.checkpoint.usage());
        assertThat(expired.stopCode()).isEqualTo("WORKFLOW_GOAL_DEADLINE");
    }

    BoundedWorkflowRunnerTest.Fixture delegationActions() { return new BoundedWorkflowRunnerTest.Fixture() {
        public List<WorkflowDelegationProfile> delegatedProfiles() {
            return List.of(new WorkflowDelegationProfile("api@1", "published-hash", "API focused subtask.", List.of("write")));
        }
        public void verifyDelegation(WorkflowDelegation delegation) {
            if (!delegation.sourceReferences().equals(List.of("owned-source@1"))) throw new SecurityException("SOURCE_DENIED");
        }
        public void verifyDelegatedAction(WorkflowDelegation delegation, String action, Map<String, Object> parameters) {
            verifyDelegation(delegation);
        }
    }; }
    Decision delegate() { return new Decision(DecisionType.DELEGATE, null, null, null, null,
        new WorkflowDelegation("api@1", "Prepare the selected operation", List.of("owned-op"), List.of("owned-source@1"))); }

    @Test void delegatedNonCompleteOutcomeReturnsToParentUsingTheSameJournalAndAllowance() {
        var calls = new AtomicInteger();
        var result = runner.run(request, policy(), (checkpoint, allowance) -> {
            allowance.modelCall();
            return switch (calls.incrementAndGet()) {
                case 1 -> delegate();
                case 2 -> action(7);
                case 3 -> new Decision(DecisionType.NEEDS_INPUT, null, null, "A real credential is missing", List.of("Credential reference?"));
                default -> {
                    assertThat(checkpoint.goal().activeDelegation()).isNull();
                    assertThat(checkpoint.steps().getFirst().observation()).containsEntry("state", "NEEDS_INPUT");
                    yield done();
                }
            };
        }, delegationActions());
        assertThat(result.state()).isEqualTo(State.COMPLETE);
        assertThat(result.usage()).isEqualTo(new WorkflowAllowance.Usage(2, 1, 4, 0));
        assertThat(result.steps()).extracting(Step::operationId).containsExactly("work:step:1", "work:step:2");
    }

    @Test void delegatedActionCannotWidenProfileOrRecursivelyDelegate() {
        for (Decision denied : List.of(new Decision(DecisionType.ACTION, "read", Map.of(), null, null), delegate())) {
            var isolated = new BoundedWorkflowRunnerTest.Store(); var calls = new AtomicInteger();
            var result = new BoundedWorkflowRunner(isolated, clock).run(request, policy(), (c, a) -> {
                a.modelCall(); return calls.incrementAndGet() == 1 ? delegate() : denied;
            }, delegationActions());
            assertThat(result.state()).isEqualTo(State.STOPPED);
            assertThat(result.steps()).hasSize(1);
            assertThat(result.usage().writes()).isZero();
        }
    }

    @Test void compactionIsExplicitBoundedAndDoesNotMutateTheDurableJournal() throws Exception {
        var steps = new ArrayList<Step>();
        for (int i = 0; i < 100; i++) steps.add(new Step("work:step:" + i, "read", false, Map.of(), "hash" + i,
            Effect.EXECUTED, Map.of("large", "x".repeat(100000)), null));
        var checkpoint = new WorkflowCheckpoint("work", "request", "authority", "definition", request.deadline(), 1,
            new WorkflowAllowance.Usage(100, 0, 101, 0), steps, State.RUNNING, null, List.of(), null);
        var mapper = new ObjectMapper(); var window = WorkflowObservationWindow.project(checkpoint, mapper);
        assertThat(window).containsEntry("totalStepCount", 100).containsEntry("hasMore", true).containsEntry("stepOffset", 92);
        assertThat(mapper.writeValueAsString(window)).hasSizeLessThan(80000).contains("truncated");
        assertThat(checkpoint.steps()).hasSize(100);
        assertThat(checkpoint.steps().getFirst().observation().get("large").toString()).hasSize(100000);
    }

    @Test void rateLimitYieldsDurablyAndHonorsProviderRetryAfterWithoutAnotherCall() {
        var outcome = new ProviderOutcome("workflow", "model", 8192, "private-request", null, null, null, null, 0,
            "RATE_LIMIT", 60000L);
        var result = runner.run(request, policy(), (c, a) -> {
            a.modelCall(); throw new PlanningFinding("WORKFLOW_CALL_ERROR", "Provider rate limited the request", outcome, true);
        }, actions());
        assertThat(result.state()).isEqualTo(State.YIELDED);
        assertThat(result.goal().nextEligibleAt()).isEqualTo(now.plusSeconds(60));
        assertThat(runner.run(request, policy(), (c, a) -> { throw new AssertionError(); }, actions())).isEqualTo(result);
        assertThat(result.usage().modelCalls()).isEqualTo(1);
    }

    @Test void fundingAdmissionDenialPausesRatherThanConsumingRecoveryCalls() {
        var outcome = new ProviderOutcome("workflow", "model", 8192, null, null, null, null, null, 0, "ADMISSION_DENIED");
        var result = runner.run(request, policy(), (c, a) -> {
            a.modelCall(); throw new PlanningFinding("WORKFLOW_CALL_ERROR", "Provider admission was denied", outcome, true);
        }, actions());
        assertThat(result.state()).isEqualTo(State.PAUSED);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_PROVIDER_ADMISSION_DENIED");
        assertThat(result.usage().modelCalls()).isEqualTo(1);
    }

    @Test void budgetGuardPausesAfterAReceiptAndAuthorizedResumeKeepsUsageAndOperationIds() {
        var denied = new AtomicBoolean();
        var actions = new BoundedWorkflowRunnerTest.Fixture() {
            public void verifyAuthority(Request request) {
                if (denied.get()) throw new OrchestrationControlException("PROVIDER_BUDGET_TOKEN_LIMIT", "Budget exhausted");
            }
            public Outcome invoke(Step step, WorkflowAllowance allowance) {
                var outcome = super.invoke(step, allowance);
                if (step.operationId().equals("work:step:1")) denied.set(true);
                return outcome;
            }
        };
        var paused = runner.run(request, policy(), (c, a) -> { a.modelCall(); return action(7); }, actions);
        assertThat(paused.state()).isEqualTo(State.PAUSED);
        assertThat(paused.stopCode()).isEqualTo("PROVIDER_BUDGET_TOKEN_LIMIT");
        assertThat(paused.usage()).isEqualTo(new WorkflowAllowance.Usage(1, 1, 1, 0));
        assertThat(paused.steps().getFirst().effect()).isEqualTo(Effect.EXECUTED);
        assertThat(actions.dispatched).containsExactly("work:step:1");
        assertThatThrownBy(() -> runner.resumeGoal(request, policy(), (c, a) -> { throw new AssertionError(); }, actions))
            .isInstanceOf(OrchestrationControlException.class);
        assertThat(store.checkpoint).isEqualTo(paused);
        denied.set(false);
        var resumed = runner.resumeGoal(request, policy(), (c, a) -> {
            a.modelCall(); return c.steps().size() < 2 ? action(8) : done();
        }, actions);
        assertThat(resumed.state()).isEqualTo(State.COMPLETE);
        assertThat(resumed.usage()).isEqualTo(new WorkflowAllowance.Usage(2, 2, 3, 0));
        assertThat(resumed.steps().getFirst()).isEqualTo(paused.steps().getFirst());
        assertThat(resumed.requestFingerprint()).isEqualTo(paused.requestFingerprint());
        assertThat(resumed.authorityFingerprint()).isEqualTo(paused.authorityFingerprint());
        assertThat(resumed.deadline()).isEqualTo(paused.deadline());
        assertThat(resumed.goal().segmentNumber()).isEqualTo(2);
        assertThat(actions.dispatched).containsExactly("work:step:1", "work:step:2");
    }

    @Test void capturedBudgetDenialAfterProviderFailurePausesBeforeYieldAndRetainsFailureEvidence() {
        var denied = new AtomicBoolean();
        var actions = new BoundedWorkflowRunnerTest.Fixture() {
            public void verifyAuthority(Request request) {
                if (denied.get()) throw new OrchestrationControlException("PROVIDER_BUDGET_LIMIT_EXCEEDED", "Budget exhausted");
            }
        };
        var outcome = new ProviderOutcome("workflow", "model", 8192, null, null, null, null, null, 0, "LIMIT_EXCEEDED");
        var paused = runner.run(request, policy(), (c, a) -> {
            a.modelCall(); denied.set(true);
            throw new PlanningFinding("WORKFLOW_CALL_ERROR", "Provider call failed", outcome, true);
        }, actions);
        assertThat(paused.state()).isEqualTo(State.PAUSED);
        assertThat(paused.stopCode()).isEqualTo("PROVIDER_BUDGET_LIMIT_EXCEEDED");
        assertThat(paused.usage()).isEqualTo(new WorkflowAllowance.Usage(0, 0, 1, 0));
        assertThat(paused.goal().recentFailures()).containsExactly(new WorkflowGoalProgress.Failure("WORKFLOW_CALL_ERROR", outcome));
        assertThat(actions.dispatched).isEmpty();
        denied.set(false);
        var resumed = runner.resumeGoal(request, policy(), (c, a) -> {
            a.modelCall(); return c.steps().isEmpty() ? action(7) : done();
        }, actions);
        assertThat(resumed.state()).isEqualTo(State.COMPLETE);
        assertThat(resumed.usage()).isEqualTo(new WorkflowAllowance.Usage(1, 1, 3, 0));
        assertThat(resumed.goal().recentFailures()).containsExactlyElementsOf(paused.goal().recentFailures());
        assertThat(actions.dispatched).containsExactly("work:step:1");
    }

    @Test void ordinaryBudgetGuardStillStopsAndSecurityDenialNeverBecomesResumable() {
        var bounded = new WorkflowPolicy(true, 4, 3, 4, 2, Duration.ofSeconds(120));
        var stopped = runner.run(request, bounded, (c, a) -> {
            a.modelCall(); throw new OrchestrationControlException("PROVIDER_BUDGET_TOKEN_LIMIT", "Budget exhausted");
        }, actions());
        assertThat(stopped.state()).isEqualTo(State.STOPPED);
        assertThat(stopped.stopCode()).isEqualTo("PROVIDER_BUDGET_TOKEN_LIMIT");
        var isolated = new BoundedWorkflowRunnerTest.Store();
        var securityDenied = new BoundedWorkflowRunner(isolated, clock).run(request, policy(), (c, a) -> {
            a.modelCall(); throw new SecurityException("SCOPE_REVOKED");
        }, actions());
        assertThat(securityDenied.state()).isEqualTo(State.STOPPED);
        assertThat(isolated.checkpoint.usage().modelCalls()).isEqualTo(1);
        assertThat(new BoundedWorkflowRunner(isolated, clock).resumeGoal(request, policy(),
            (c, a) -> { throw new AssertionError(); }, actions())).isEqualTo(securityDenied);
    }

    @Test void newReceiptIdsDoNotDisguiseRepeatingTheSameWriteAsProgress() {
        var policy = new WorkflowPolicy(true, 4, 3, 4, 2, Duration.ofSeconds(120),
            new WorkflowGoalPolicy(true, Duration.ofHours(1), Duration.ZERO, 3, 4, List.of()));
        var actions = new BoundedWorkflowRunnerTest.Fixture() {
            public Outcome invoke(Step step, WorkflowAllowance allowance) {
                return new Outcome(Effect.EXECUTED, Map.of("receipt", step.operationId()), null, false);
            }
        };
        WorkflowCheckpoint result = null;
        for (int i = 0; i < 8; i++) {
            result = runner.run(request, policy, (c, a) -> { a.modelCall(); return action(1); }, actions);
            if (result.state() != State.YIELDED) break;
        }
        assertThat(result.state()).isEqualTo(State.PAUSED);
        assertThat(result.stopCode()).isEqualTo("WORKFLOW_NO_PROGRESS");
        assertThat(result.usage().writes()).isEqualTo(4);
    }
}
