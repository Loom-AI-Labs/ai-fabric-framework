package ai.fabric.intent.action.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultGovernedActionInvocationServiceTest {

    private AIActionRegistry registry;
    private AIActionHandler handler;
    private AIActionMetaData metadata;
    private DefaultGovernedActionInvocationService service;

    @BeforeEach
    void setUp() {
        registry = mock(AIActionRegistry.class);
        handler = mock(AIActionHandler.class);
        metadata = AIActionMetaData.builder()
            .name("update_payment")
            .accessMode(ActionAccessMode.READ_WRITE)
            .confirmationRequired(true)
            .requiredParameters(Set.of("last4"))
            .build();
        when(registry.findMetadata("update_payment")).thenReturn(Optional.of(metadata));
        when(registry.findHandler("update_payment")).thenReturn(Optional.of(handler));
        when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(handler.requiresConfirmation()).thenReturn(true);
        when(handler.getConfirmationMessage(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn("Update payment method?");
        service = new DefaultGovernedActionInvocationService(registry);
    }

    @Test
    void deniesActionOutsideEffectiveProfileWithoutCallingHandler() {
        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), Set.of()),
            ActionConfirmationState.CONFIRMED,
            Map.of("last4", "4242")
        ));

        assertThat(outcome.status()).isEqualTo(GovernedActionInvocationStatus.DENIED);
        assertThat(outcome.publicFailure().reason()).isEqualTo("ACTION_NOT_IN_EFFECTIVE_PROFILE");
        verify(handler, never()).executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void returnsConfirmationWithoutExecutingWrite() {
        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.NOT_CONFIRMED,
            Map.of("last4", "4242")
        ));

        assertThat(outcome.status())
            .isEqualTo(GovernedActionInvocationStatus.CONFIRMATION_REQUIRED);
        assertThat(outcome.actionResult().getErrorCode()).isEqualTo("CONFIRMATION_REQUIRED");
        verify(handler, never()).executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void executesOnlyAfterCapabilityAuthorizationParametersAndConfirmationPass() {
        ActionResult actionResult = ActionResult.builder()
            .success(true)
            .message("Updated")
            .build();
        when(handler.executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(actionResult);

        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.CONFIRMED,
            Map.of("last4", "4242")
        ));

        assertThat(outcome.status()).isEqualTo(GovernedActionInvocationStatus.EXECUTED);
        assertThat(outcome.actionResult()).isSameAs(actionResult);
        verify(handler).executeAction(
            org.mockito.ArgumentMatchers.eq(Map.of("last4", "4242")),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void rejectsMissingRequiredParametersBeforeExecution() {
        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.CONFIRMED,
            Map.of()
        ));

        assertThat(outcome.status()).isEqualTo(GovernedActionInvocationStatus.INVALID);
        assertThat(outcome.publicFailure().reason())
            .isEqualTo("ACTION_REQUIRED_PARAMETERS_MISSING");
        verify(handler, never()).executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    private GovernedActionInvocation invocation(
        EffectiveCapabilityProfile profile,
        ActionConfirmationState confirmationState,
        Map<String, Object> parameters
    ) {
        OrchestrationContext orchestrationContext = OrchestrationContext.forUser("user-1");
        return new GovernedActionInvocation(
            "update_payment",
            parameters,
            new ActionContext(orchestrationContext, null, parameters),
            null,
            profile,
            confirmationState,
            List.of()
        );
    }

    private EffectiveCapabilityProfile profile(Set<String> reads, Set<String> writes) {
        Set<String> visible = new java.util.LinkedHashSet<>(reads);
        visible.addAll(writes);
        return new EffectiveCapabilityProfile(
            "DEFAULT",
            "resolver",
            true,
            Set.of("account"),
            visible,
            reads,
            writes,
            OrchestrationPolicy.RagBudgets.defaults(),
            OrchestrationPolicy.ReadActionResolutionPolicy.defaults(),
            "test-profile-hash"
        );
    }
}
