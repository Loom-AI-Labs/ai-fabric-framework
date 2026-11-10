package ai.fabric.intent.action.invocation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.intent.action.AIActionHandler;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionParamSchema;
import ai.fabric.intent.action.AIActionParamType;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.action.ActionContext;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.action.InvocationConfirmationPolicy;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilityProfile;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.control.OrchestrationControlException;
import ai.fabric.intent.orchestration.control.OrchestrationTurnGuard;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

class DefaultGovernedActionInvocationServiceTest {

    private AIActionRegistry registry;
    private AIActionHandler handler;
    private AIActionMetaData metadata;
    private DefaultGovernedActionInvocationService service;
    private OrchestrationTurnGuard guard;

    @BeforeEach
    void setUp() {
        registry = mock(AIActionRegistry.class);
        handler = mock(AIActionHandler.class);
        guard = mock(OrchestrationTurnGuard.class);
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
    void executesWithoutDuplicateConfirmationWhenInvocationPolicyWaivesIt() {
        AIActionHandler dynamicHandler = mock(
            AIActionHandler.class,
            Mockito.withSettings().extraInterfaces(InvocationConfirmationPolicy.class)
        );
        InvocationConfirmationPolicy policy = (InvocationConfirmationPolicy) dynamicHandler;
        when(registry.findHandler("update_payment")).thenReturn(Optional.of(dynamicHandler));
        when(dynamicHandler.getActionMetadata()).thenReturn(metadata);
        when(dynamicHandler.requiresConfirmation()).thenReturn(true);
        when(dynamicHandler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(policy.requiresConfirmation(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(false);
        when(dynamicHandler.executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(ActionResult.builder().success(true).message("Transferred").build());

        GovernedActionInvocationOutcome preflight = service.invoke(invocation(
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.PROPOSAL_PREFLIGHT,
            Map.of("last4", "4242")
        ));
        assertThat(preflight.status()).isEqualTo(GovernedActionInvocationStatus.DENIED);
        assertThat(preflight.publicFailure().reason()).isEqualTo("ACTION_PROPOSAL_POLICY_CHANGED");
        verify(dynamicHandler, never()).executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        );

        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.NOT_CONFIRMED,
            Map.of("last4", "4242")
        ));

        assertThat(outcome.status()).isEqualTo(GovernedActionInvocationStatus.EXECUTED);
        verify(dynamicHandler).executeAction(
            org.mockito.ArgumentMatchers.eq(Map.of("last4", "4242")),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void keepsConfirmationWhenInvocationPolicyRequiresIt() {
        AIActionHandler dynamicHandler = mock(
            AIActionHandler.class,
            Mockito.withSettings().extraInterfaces(InvocationConfirmationPolicy.class)
        );
        InvocationConfirmationPolicy policy = (InvocationConfirmationPolicy) dynamicHandler;
        when(registry.findHandler("update_payment")).thenReturn(Optional.of(dynamicHandler));
        when(dynamicHandler.getActionMetadata()).thenReturn(metadata);
        when(dynamicHandler.requiresConfirmation()).thenReturn(true);
        when(dynamicHandler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        when(dynamicHandler.getConfirmationMessage(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn("Talk to a person?");
        when(policy.requiresConfirmation(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);

        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.NOT_CONFIRMED,
            Map.of("last4", "4242")
        ));

        assertThat(outcome.status()).isEqualTo(GovernedActionInvocationStatus.CONFIRMATION_REQUIRED);
        verify(dynamicHandler, never()).executeAction(
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

    @Test
    void rejectsOutOfRangeParametersBeforeCallingTheHandler() {
        AIActionMetaData readMetadata = AIActionMetaData.builder()
            .name("update_payment")
            .accessMode(ActionAccessMode.READ)
            .confirmationRequired(false)
            .parameterSchemas(Map.of(
                "limit",
                AIActionParamSchema.builder()
                    .name("limit")
                    .type(AIActionParamType.INTEGER)
                    .min(1L)
                    .max(24L)
                    .build()
            ))
            .build();
        when(registry.findMetadata("update_payment"))
            .thenReturn(Optional.of(readMetadata));
        when(handler.requiresConfirmation()).thenReturn(false);

        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of("update_payment"), Set.of()),
            ActionConfirmationState.CONFIRMED,
            Map.of("limit", 50)
        ));

        assertThat(outcome.status()).isEqualTo(GovernedActionInvocationStatus.INVALID);
        assertThat(outcome.publicFailure().reason()).isEqualTo("ACTION_PARAMETER_INVALID");
        assertThat(outcome.actionResult().getErrorCode()).isEqualTo("ACTION_PARAMETER_INVALID");
        verify(handler, never()).executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void reportsUnknownOutcomeWhenWriteHandlerThrows() {
        when(handler.executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenThrow(new IllegalStateException("connection lost after write"));
        when(handler.handleError(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(ActionResult.builder()
            .success(false)
            .message("Write outcome cannot be verified.")
            .errorCode("WRITE_OUTCOME_UNKNOWN")
            .build());

        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.CONFIRMED,
            Map.of("last4", "4242")
        ));

        assertThat(outcome.status())
            .isEqualTo(GovernedActionInvocationStatus.OUTCOME_UNKNOWN);
        assertThat(outcome.publicFailure().reason())
            .isEqualTo("WRITE_OUTCOME_UNKNOWN");
        assertThat(outcome.publicFailure().retryable()).isFalse();
    }

    @Test
    void preservesOrdinaryFailureForReadHandlerExceptions() {
        AIActionMetaData readMetadata = AIActionMetaData.builder()
            .name("update_payment")
            .accessMode(ActionAccessMode.READ)
            .confirmationRequired(false)
            .requiredParameters(Set.of("last4"))
            .build();
        when(registry.findMetadata("update_payment"))
            .thenReturn(Optional.of(readMetadata));
        when(handler.requiresConfirmation()).thenReturn(false);
        when(handler.executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        )).thenThrow(new IllegalStateException("read unavailable"));

        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of("update_payment"), Set.of()),
            ActionConfirmationState.CONFIRMED,
            Map.of("last4", "4242")
        ));

        assertThat(outcome.status())
            .isEqualTo(GovernedActionInvocationStatus.FAILED);
    }

    @Test
    void revalidatesHostTurnGuardImmediatelyBeforeExecution() {
        OrchestrationTurnGuard guard = mock(OrchestrationTurnGuard.class);
        org.mockito.Mockito.doThrow(new OrchestrationControlException(
            "AI_TURN_LEASE_STALE",
            "Conversation control changed."
        )).when(guard).verifyBeforeAction(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .orchestrationTurnGuard(guard)
            .build();

        GovernedActionInvocationOutcome outcome = service.invoke(new GovernedActionInvocation(
            "update_payment",
            Map.of("last4", "4242"),
            new ActionContext(orchestrationContext, null, Map.of("last4", "4242")),
            null,
            profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.CONFIRMED,
            List.of()
        ));

        assertThat(outcome.status()).isEqualTo(GovernedActionInvocationStatus.DENIED);
        assertThat(outcome.publicFailure().reason()).isEqualTo("AI_TURN_LEASE_STALE");
        verify(guard, never()).afterAction(
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
        verify(handler, never()).executeAction(
            org.mockito.ArgumentMatchers.anyMap(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @ParameterizedTest
    @CsvSource({
        "success, EXECUTED, false", "failure, FAILED, false",
        "null, FAILED, false", "writeException, OUTCOME_UNKNOWN, false",
        "readException, FAILED, false",
        "success, EXECUTED, true", "failure, FAILED, true",
        "null, FAILED, true", "writeException, OUTCOME_UNKNOWN, true",
        "readException, FAILED, true"
    })
    @ExtendWith(OutputCaptureExtension.class)
    void reportsExactlyTheDispatchedOutcomeEvenWhenCompletionHookThrows(
        String scenario,
        GovernedActionInvocationStatus expected,
        boolean hookThrows,
        CapturedOutput logs
    ) {
        boolean read = scenario.equals("readException");
        boolean handlerThrows = scenario.endsWith("Exception");
        metadata.setAccessMode(read ? ActionAccessMode.READ : ActionAccessMode.READ_WRITE);
        ActionResult result = ActionResult.builder()
            .success(scenario.equals("success"))
            .errorCode(scenario.equals("failure") ? "BUSINESS_FAILURE" : null)
            .build();
        AtomicBoolean dispatched = new AtomicBoolean();
        when(handler.executeAction(
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()
        )).thenAnswer(call -> {
            dispatched.set(true);
            if (handlerThrows) {
                throw new IllegalStateException("handler failed");
            }
            return scenario.equals("null") ? null : result;
        });
        Mockito.doAnswer(call -> {
            assertThat(dispatched).isTrue();
            if (hookThrows) {
                throw new IllegalStateException("private receipt payload");
            }
            return null;
        }).when(guard).afterAction(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
        Map<String, Object> parameters = Map.of("last4", "4242");

        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            read ? profile(Set.of("update_payment"), Set.of())
                : profile(Set.of(), Set.of("update_payment")),
            ActionConfirmationState.CONFIRMED, parameters
        ));

        assertThat(outcome.status()).isEqualTo(expected);
        if (!handlerThrows && !scenario.equals("null")) {
            assertThat(outcome.actionResult()).isSameAs(result);
        }
        if (scenario.equals("null")) {
            assertThat(outcome.publicFailure().reason()).isEqualTo("ACTION_EMPTY_RESULT");
        }
        var order = Mockito.inOrder(handler, guard);
        order.verify(guard).verifyBeforeAction(
            org.mockito.ArgumentMatchers.same(metadata), org.mockito.ArgumentMatchers.any()
        );
        order.verify(handler).executeAction(
            org.mockito.ArgumentMatchers.eq(parameters), org.mockito.ArgumentMatchers.any()
        );
        order.verify(guard).afterAction(
            org.mockito.ArgumentMatchers.same(metadata),
            org.mockito.ArgumentMatchers.argThat(context ->
                context.orchestrationContext().getOrchestrationTurnGuard() == guard
                    && context.actionParams().equals(parameters)),
            org.mockito.ArgumentMatchers.same(outcome)
        );
        verify(handler).executeAction(
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()
        );
        verify(handler, Mockito.times(handlerThrows ? 1 : 0)).handleError(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
        );
        if (hookThrows) {
            assertThat(logs.getOut()).contains(
                "Action completion hook failed: action=update_payment outcome=" + expected,
                "cause=java.lang.IllegalStateException"
            ).doesNotContain("private receipt payload");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"profile", "schema", "authorization", "authorizationException", "confirmation", "guard"})
    void neverReportsCompletionBeforeDispatch(String boundary) {
        if (boundary.equals("authorization")) {
            when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any())).thenReturn(false);
        }
        if (boundary.equals("authorizationException")) {
            when(handler.validateActionAllowed(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("authorization unavailable"));
        }
        if (boundary.equals("guard")) {
            Mockito.doThrow(new OrchestrationControlException("STALE", "Stale turn"))
                .when(guard).verifyBeforeAction(
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()
                );
        }
        GovernedActionInvocationOutcome outcome = service.invoke(invocation(
            profile(Set.of(), boundary.equals("profile") ? Set.of() : Set.of("update_payment")),
            boundary.equals("confirmation") ? ActionConfirmationState.NOT_CONFIRMED
                : ActionConfirmationState.CONFIRMED,
            boundary.equals("schema") ? Map.of() : Map.of("last4", "4242")
        ));

        assertThat(outcome.executed()).isFalse();
        verify(handler, never()).executeAction(
            org.mockito.ArgumentMatchers.anyMap(), org.mockito.ArgumentMatchers.any()
        );
        verify(guard, never()).afterAction(
            org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        );
    }

    private GovernedActionInvocation invocation(
        EffectiveCapabilityProfile profile,
        ActionConfirmationState confirmationState,
        Map<String, Object> parameters
    ) {
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1").orchestrationTurnGuard(guard).build();
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
