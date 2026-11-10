package ai.fabric.execution.action;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.invocation.ActionConfirmationState;
import ai.fabric.intent.action.invocation.DefaultGovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocation;
import ai.fabric.intent.action.invocation.GovernedActionInvocationOutcome;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocationStatus;
import ai.fabric.intent.orchestration.control.OrchestrationControlException;
import ai.fabric.intent.orchestration.control.OrchestrationTurnGuard;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ActionProposalCoordinatorGuardTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void guardedConfirmationCapturesCompletionAndReplaysDespiteLaterExpiry(boolean hookThrows) {
        Fixture fixture = new Fixture();
        ActionProposalDecisionRequest request = confirm(fixture.action.propose());
        OrchestrationTurnGuard guard = mock(OrchestrationTurnGuard.class);
        AtomicBoolean expired = new AtomicBoolean();
        doAnswer(call -> {
            if (expired.get()) {
                throw expiredGuard();
            }
            return null;
        }).when(guard).verifyBeforeOrchestration();
        doAnswer(call -> {
            if (expired.get()) {
                throw expiredGuard();
            }
            return null;
        }).when(guard).verifyBeforeAction(any(), any());
        when(fixture.handler.executeAction(anyMap(), any())).thenAnswer(call -> {
            expired.set(true);
            return fixture.result;
        });
        if (hookThrows) {
            doThrow(new IllegalStateException("Receipt sink unavailable"))
                .when(guard).afterAction(any(), any(), any());
        }

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            request, fixture.action.trustedContext, guard
        );
        ActionProposalDecisionResult replay = fixture.coordinator.decide(
            request, fixture.action.trustedContext, guard
        );

        assertThat(result.status()).isEqualTo(ActionProposalReceiptStatus.SUCCEEDED);
        assertThat(result.failure()).isNull();
        assertThat(replay).isEqualTo(result);
        assertThat(fixture.coordinator.decide(request, fixture.action.trustedContext))
            .isEqualTo(result);
        assertGuardOnInvocations(fixture, guard, 2);
        ArgumentCaptor<ActionContext> actionContext = ArgumentCaptor.forClass(ActionContext.class);
        verify(fixture.handler).executeAction(anyMap(), actionContext.capture());
        ArgumentCaptor<GovernedActionInvocationOutcome> outcome =
            ArgumentCaptor.forClass(GovernedActionInvocationOutcome.class);
        verify(guard).verifyBeforeOrchestration();
        verify(guard).verifyBeforeAction(same(fixture.action.metadata), same(actionContext.getValue()));
        verify(guard).afterAction(
            same(fixture.action.metadata), same(actionContext.getValue()), outcome.capture()
        );
        assertThat(outcome.getValue().status()).isEqualTo(GovernedActionInvocationStatus.EXECUTED);
        assertThat(outcome.getValue().actionResult()).isSameAs(fixture.result);
        verify(guard, never()).verifyBeforeResultCommit();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expiredGuardDeniesDispatchAtDecisionOrFinalActionBoundary(boolean finalBoundary) {
        Fixture fixture = new Fixture();
        ActionProposalDecisionRequest request = confirm(fixture.action.propose());
        OrchestrationTurnGuard guard = mock(OrchestrationTurnGuard.class);
        if (finalBoundary) {
            doThrow(expiredGuard()).when(guard).verifyBeforeAction(any(), any());
        } else {
            doThrow(expiredGuard()).when(guard).verifyBeforeOrchestration();
        }

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            request, fixture.action.trustedContext, guard
        );

        assertThat(result.status()).isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(result.failure().reason()).isEqualTo("DEADLINE_EXCEEDED");
        ActionProposalDecisionResult replay = fixture.coordinator.decide(
            request, fixture.action.trustedContext, guard
        );
        assertThat(replay.status()).isEqualTo(ActionProposalReceiptStatus.FAILED);
        assertThat(replay.failure().reason()).isEqualTo("DEADLINE_EXCEEDED");
        verify(fixture.handler, never()).executeAction(anyMap(), any());
        verify(guard, never()).afterAction(any(), any(), any());
        verify(guard).verifyBeforeOrchestration();
        if (finalBoundary) {
            assertGuardOnInvocations(fixture, guard, 2);
            verify(guard).verifyBeforeAction(any(), any());
        } else {
            verifyNoInteractions(fixture.invocations);
            assertThat(fixture.action.repository.findById(request.receiptId()).orElseThrow()
                .executionStartedAt()).isNull();
        }
    }

    @ParameterizedTest
    @EnumSource(value = ActionProposalReceiptStatus.class, names = {"CONFIRMED", "EXECUTING"})
    void retainsGuardAcrossBothConfirmationCasRetries(ActionProposalReceiptStatus racedStatus) {
        InMemoryActionProposalReceiptRepository repository = spy(new InMemoryActionProposalReceiptRepository());
        Fixture fixture = new Fixture(repository);
        ActionProposalDecisionRequest request = confirm(fixture.action.propose());
        AtomicBoolean raced = new AtomicBoolean();
        doAnswer(call -> {
            ActionProposalReceipt updated = call.getArgument(1);
            if (updated.status() == racedStatus && raced.compareAndSet(false, true)) {
                return false;
            }
            return call.callRealMethod();
        }).when(repository).compareAndSet(any(), any());
        OrchestrationTurnGuard guard = mock(OrchestrationTurnGuard.class);

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            request, fixture.action.trustedContext, guard
        );

        assertThat(raced).isTrue();
        assertThat(result.succeeded()).isTrue();
        assertGuardOnInvocations(fixture, guard, 3);
        verify(guard, times(2)).verifyBeforeOrchestration();
        verify(guard).verifyBeforeAction(any(), any());
        verify(guard).afterAction(any(), any(), any());
        verify(fixture.handler).executeAction(anyMap(), any());
        assertThat(fixture.coordinator.decide(request, fixture.action.trustedContext, guard))
            .isEqualTo(result);
        verify(fixture.handler).executeAction(anyMap(), any());
        verify(guard).afterAction(any(), any(), any());
    }

    @Test
    void legacyAndExplicitNullCallsRetainUnguardedExecutionAndReplay() {
        Fixture fixture = new Fixture();
        ActionProposalDecisionRequest request = confirm(fixture.action.propose());

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            request, fixture.action.trustedContext
        );
        ActionProposalDecisionResult replay = fixture.coordinator.decide(
            request, fixture.action.trustedContext, null
        );

        assertThat(result.succeeded()).isTrue();
        assertThat(replay).isEqualTo(result);
        assertGuardOnInvocations(fixture, null, 2);
        verify(fixture.handler).executeAction(anyMap(), any());
    }

    @Test
    void rejectionDoesNotInvokeSuppliedGuard() {
        Fixture fixture = new Fixture();
        ActionProposalView proposal = fixture.action.propose();
        OrchestrationTurnGuard guard = mock(OrchestrationTurnGuard.class);
        doThrow(expiredGuard()).when(guard).verifyBeforeOrchestration();

        ActionProposalDecisionResult result = fixture.coordinator.decide(
            new ActionProposalDecisionRequest(proposal.receiptId(), ActionProposalDecision.REJECT),
            fixture.action.trustedContext, guard
        );

        assertThat(result.status()).isEqualTo(ActionProposalReceiptStatus.REJECTED);
        verifyNoInteractions(guard, fixture.invocations);
        verify(fixture.handler, never()).executeAction(anyMap(), any());
    }

    private void assertGuardOnInvocations(Fixture fixture, OrchestrationTurnGuard guard, int count) {
        ArgumentCaptor<GovernedActionInvocation> invocations =
            ArgumentCaptor.forClass(GovernedActionInvocation.class);
        verify(fixture.invocations, times(count)).invoke(invocations.capture());
        assertThat(invocations.getAllValues()).allSatisfy(invocation -> {
            assertThat(invocation.actionContext().orchestrationContext().getOrchestrationTurnGuard())
                .isSameAs(guard);
            assertThat(invocation.actionContext().pipelineContext().getOrchestrationContext()
                .getOrchestrationTurnGuard()).isSameAs(guard);
        });
        assertThat(invocations.getAllValues().getFirst().confirmationState())
            .isEqualTo(ActionConfirmationState.PROPOSAL_PREFLIGHT);
        assertThat(invocations.getAllValues().getLast().confirmationState())
            .isEqualTo(ActionConfirmationState.CONFIRMED);
    }

    private ActionProposalDecisionRequest confirm(ActionProposalView proposal) {
        return new ActionProposalDecisionRequest(proposal.receiptId(), ActionProposalDecision.CONFIRM);
    }

    private OrchestrationControlException expiredGuard() {
        return new OrchestrationControlException("DEADLINE_EXCEEDED", "Task deadline exceeded.");
    }

    private static final class Fixture {
        private final ActionProposalTestFixture action;
        private final AIActionHandler handler = mock(AIActionHandler.class);
        private final ActionResult result = ActionResult.builder().success(true).message("Updated").build();
        private final GovernedActionInvocationService invocations;
        private final ActionProposalCoordinator coordinator;

        private Fixture() {
            this(new InMemoryActionProposalReceiptRepository());
        }

        private Fixture(ActionProposalReceiptRepository repository) {
            action = new ActionProposalTestFixture(repository);
            when(action.actionRegistry.findHandler(ActionProposalTestFixture.ACTION))
                .thenReturn(Optional.of(handler));
            when(handler.requiresConfirmation()).thenReturn(true);
            when(handler.validateActionAllowed(any())).thenReturn(true);
            when(handler.getConfirmationMessage(anyMap(), any())).thenReturn("Update the address?");
            when(handler.executeAction(anyMap(), any())).thenReturn(result);
            invocations = spy(new DefaultGovernedActionInvocationService(action.actionRegistry));
            coordinator = action.coordinator(invocations);
        }
    }
}
