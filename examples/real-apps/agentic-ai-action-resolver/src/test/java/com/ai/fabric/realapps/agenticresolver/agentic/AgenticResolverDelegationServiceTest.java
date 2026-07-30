package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.delegation.SpecialistDelegationRequest;
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgenticResolverDelegationServiceTest {

    private static final Instant NOW = Instant.parse(
        "2026-07-29T12:00:00Z"
    );

    private final SpecialistClientFactory clientFactory = mock(
        SpecialistClientFactory.class
    );
    private final SpecialistClient<
        AccountDelegationCoordinatorRequest,
        AccountDelegationDecision
    > coordinatorClient = mock(SpecialistClient.class);
    private final SpecialistDelegationGateway delegationGateway = mock(
        SpecialistDelegationGateway.class
    );
    private final AgenticResolverSessionService sessions = mock(
        AgenticResolverSessionService.class
    );
    private AgenticResolverExecutionService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        SpecialistClient<
            AccountResolutionRequest,
            AccountResolutionResult
        > accountClient = mock(SpecialistClient.class);
        SpecialistClient<
            BillingResolutionAssessmentRequest,
            BillingResolutionAssessmentResult
        > billingClient = mock(SpecialistClient.class);

        when(clientFactory.bind(
            AccountResolverSpecialists.SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class
        )).thenReturn(accountClient);
        when(clientFactory.bind(
            AccountResolverSpecialists.READ_SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class
        )).thenReturn(accountClient);
        when(clientFactory.bind(
            AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID,
            BillingResolutionAssessmentRequest.class,
            BillingResolutionAssessmentResult.class
        )).thenReturn(billingClient);
        when(clientFactory.bind(
            AccountResolverSpecialists.DELEGATION_COORDINATOR_ID,
            AccountDelegationCoordinatorRequest.class,
            AccountDelegationDecision.class
        )).thenReturn(coordinatorClient);
        when(sessions.active("session-1")).thenReturn(activeSession());

        service = new AgenticResolverExecutionService(
            clientFactory,
            mock(AIInteractiveExecutionGateway.class),
            mock(AIExecutionCoordinator.class),
            mock(ActionProposalCoordinator.class),
            sessions,
            delegationGateway,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void modelSelectedReadRouteUsesBackendContextAndOriginalTypedInput() {
        submitted(coordinatorResult(
            AccountDelegationDecision.Decision.DELEGATE,
            AccountResolverSpecialists.READ_SPECIALIST_ID
        ));
        AccountDelegationCoordinatorRequest request =
            new AccountDelegationCoordinatorRequest(
                "Why can I not place an order?",
                null,
                null
            );

        service.delegateAccountResolution(
            "session-1",
            request,
            "delegate-read-1"
        );

        ArgumentCaptor<SpecialistInvocation<
            AccountDelegationCoordinatorRequest
        >> coordinatorInvocation = ArgumentCaptor.forClass(
            SpecialistInvocation.class
        );
        verify(coordinatorClient).submit(coordinatorInvocation.capture());
        assertThat(coordinatorInvocation.getValue().input()).isEqualTo(request);
        assertThat(coordinatorInvocation.getValue().conversationBinding())
            .isNull();
        assertThat(coordinatorInvocation.getValue().deadline()).isNull();
        assertThat(coordinatorInvocation.getValue()
            .trustedExecutionContext().subject().subjectId())
            .isEqualTo(activeSession().subjectUserId().toString());
        assertThat(coordinatorInvocation.getValue()
            .trustedExecutionContext().grantedScopes())
            .contains(
                "specialist:account-resolution-coordinator@1",
                "specialist:account-resolver-read@1"
            );

        ArgumentCaptor<SpecialistDelegationRequest> delegation =
            ArgumentCaptor.forClass(SpecialistDelegationRequest.class);
        verify(delegationGateway).delegate(
            delegation.capture(),
            eq(AccountResolutionRequest.class),
            eq(AccountResolutionResult.class)
        );
        SpecialistDelegationRequest<?, ?> delegated = delegation.getValue();
        assertThat(delegated.targetSpecialistId())
            .isEqualTo(AccountResolverSpecialists.READ_SPECIALIST_ID);
        assertThat(delegated.targetInput())
            .isEqualTo(new AccountResolutionRequest(request.question()));
        assertThat(delegated.trustedExecutionContext())
            .isSameAs(coordinatorInvocation.getValue()
                .trustedExecutionContext());
        assertThat(delegated.idempotencyKey())
            .isEqualTo("delegate-read-1");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void modelSelectedBillingRouteUsesSuppliedTypedFields() {
        submitted(coordinatorResult(
            AccountDelegationDecision.Decision.DELEGATE,
            AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID
        ));
        AccountDelegationCoordinatorRequest request =
            new AccountDelegationCoordinatorRequest(
                "Assess this refund against current policy.",
                RefundRequest.ResolutionType.REFUND,
                new BigDecimal("75.00")
            );

        service.delegateAccountResolution(
            "session-1",
            request,
            "delegate-billing-1"
        );

        ArgumentCaptor<SpecialistDelegationRequest> delegation =
            ArgumentCaptor.forClass(SpecialistDelegationRequest.class);
        verify(delegationGateway).delegate(
            delegation.capture(),
            eq(BillingResolutionAssessmentRequest.class),
            eq(BillingResolutionAssessmentResult.class)
        );
        assertThat(delegation.getValue().targetInput()).isEqualTo(
            new BillingResolutionAssessmentRequest(
                request.question(),
                request.resolutionType(),
                request.amount()
            )
        );
    }

    @Test
    void completeAndFailedCoordinatorResultsDoNotInvokeAChild() {
        submitted(coordinatorResult(
            AccountDelegationDecision.Decision.COMPLETE,
            null
        ));

        AccountDelegationResponse complete =
            service.delegateAccountResolution(
                "session-1",
                new AccountDelegationCoordinatorRequest(
                    "Change an unsupported setting.",
                    null,
                    null
                ),
                "complete-1"
            );

        assertThat(complete.coordinator().succeeded()).isTrue();
        assertThat(complete.delegatedExecution()).isNull();
        verifyNoInteractions(delegationGateway);

        submitted(failedCoordinator());
        AccountDelegationResponse failed = service.delegateAccountResolution(
            "session-1",
            new AccountDelegationCoordinatorRequest(
                "Why can I not place an order?",
                null,
                null
            ),
            "failed-1"
        );

        assertThat(failed.coordinator().status())
            .isEqualTo(AIExecutionStatus.FAILED);
        assertThat(failed.delegatedExecution()).isNull();
        verify(delegationGateway, never()).delegate(
            any(),
            any(),
            any()
        );
    }

    @Test
    void requiresIdempotencyBeforeCoordinatorExecution() {
        assertThatThrownBy(() ->
            service.delegateAccountResolution(
                "session-1",
                new AccountDelegationCoordinatorRequest(
                    "Why can I not place an order?",
                    null,
                    null
                ),
                " "
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Idempotency-Key is required");

        verifyNoInteractions(coordinatorClient, delegationGateway);
    }

    private AIExecutionResult<AccountDelegationDecision> coordinatorResult(
        AccountDelegationDecision.Decision decision,
        SpecialistId target
    ) {
        return new AIExecutionResult<>(
            "coordinator-exec-1",
            AccountResolverSpecialists.DELEGATION_COORDINATOR_ID,
            AIExecutionStatus.SUCCEEDED,
            new AccountDelegationDecision(
                decision,
                target != null ? target.toString() : null,
                "The request matches the declared target."
            ),
            List.of(),
            Map.of(
                "specialistContentHash",
                "1".repeat(64),
                "executionDeadline",
                NOW.plusSeconds(60).toString()
            ),
            null,
            NOW,
            NOW.plusSeconds(1)
        );
    }

    private AIExecutionResult<AccountDelegationDecision>
    failedCoordinator() {
        return new AIExecutionResult<>(
            "coordinator-exec-failed",
            AccountResolverSpecialists.DELEGATION_COORDINATOR_ID,
            AIExecutionStatus.FAILED,
            null,
            List.of(),
            Map.of(),
            new AIExecutionFailure(
                "PROVIDER_FAILED",
                "The configured provider failed.",
                true
            ),
            NOW,
            NOW.plusSeconds(1)
        );
    }

    private void submitted(
        AIExecutionResult<AccountDelegationDecision> result
    ) {
        ExecutionHandleStatus status = result.succeeded()
            ? ExecutionHandleStatus.SUCCEEDED
            : ExecutionHandleStatus.FAILED;
        ExecutionHandle handle = new ExecutionHandle(
            result.invocationId(),
            ExecutionDurability.EPHEMERAL,
            status,
            NOW.plusSeconds(60),
            NOW.plusSeconds(3600),
            result.failure() != null ? result.failure().reason() : null
        );
        when(coordinatorClient.submit(any(SpecialistInvocation.class)))
            .thenReturn(handle);
        when(coordinatorClient.find(
            eq(result.invocationId()),
            any()
        )).thenReturn(Optional.of(
            new SpecialistExecutionSnapshot<>(handle, result)
        ));
    }

    private AgenticResolverSessionService.ActiveSession activeSession() {
        return new AgenticResolverSessionService.ActiveSession(
            "session-1",
            "missing-payment",
            UUID.fromString("00000000-0000-0000-0000-000000000092"),
            "demo:session-1:missing-payment",
            "agentic-chat:session-1:missing-payment"
        );
    }
}
