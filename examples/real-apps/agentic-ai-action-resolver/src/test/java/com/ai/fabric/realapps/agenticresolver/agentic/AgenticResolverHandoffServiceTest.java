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
import ai.fabric.execution.gateway.AIExecutionFailure;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.handoff.SpecialistHandoffRequest;
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

class AgenticResolverHandoffServiceTest {

    private static final Instant NOW = Instant.parse(
        "2026-07-29T16:00:00Z"
    );

    private final SpecialistClientFactory clientFactory = mock(
        SpecialistClientFactory.class
    );
    private final SpecialistClient<
        AccountHandoffIntakeRequest,
        AccountHandoffDecision
    > intakeClient = mock(SpecialistClient.class);
    private final SpecialistHandoffGateway handoffGateway = mock(
        SpecialistHandoffGateway.class
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
            AccountResolverSpecialists.HANDOFF_INTAKE_ID,
            AccountHandoffIntakeRequest.class,
            AccountHandoffDecision.class
        )).thenReturn(intakeClient);
        when(sessions.active("session-1")).thenReturn(activeSession());

        service = new AgenticResolverExecutionService(
            clientFactory,
            mock(AIInteractiveExecutionGateway.class),
            mock(AIExecutionCoordinator.class),
            mock(ActionProposalCoordinator.class),
            sessions,
            mock(SpecialistDelegationGateway.class),
            handoffGateway,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void readHandoffUsesBackendAuthorityAndTypedSuccessorInput() {
        submitted(predecessorResult(
            AccountHandoffDecision.Decision.HANDOFF,
            AccountResolverSpecialists.READ_SPECIALIST_ID
        ));
        AccountHandoffIntakeRequest request =
            new AccountHandoffIntakeRequest(
                "Why can I not place an order?",
                null,
                null
            );

        service.handoffAccountResolution(
            "session-1",
            request,
            "handoff-read-1"
        );

        ArgumentCaptor<SpecialistInvocation<AccountHandoffIntakeRequest>>
            intakeInvocation = ArgumentCaptor.forClass(
                SpecialistInvocation.class
            );
        verify(intakeClient).submit(intakeInvocation.capture());
        assertThat(intakeInvocation.getValue().input()).isEqualTo(request);
        assertThat(intakeInvocation.getValue().conversationBinding()).isNull();
        assertThat(intakeInvocation.getValue()
            .trustedExecutionContext().subject().subjectId())
            .isEqualTo(activeSession().subjectUserId().toString());
        assertThat(intakeInvocation.getValue()
            .trustedExecutionContext().grantedScopes())
            .contains(
                "specialist:account-resolution-intake@1",
                "specialist:account-resolver-read@1"
            );

        ArgumentCaptor<SpecialistHandoffRequest> handoff =
            ArgumentCaptor.forClass(SpecialistHandoffRequest.class);
        verify(handoffGateway).handoff(
            handoff.capture(),
            eq(AccountResolutionRequest.class),
            eq(AccountResolutionResult.class)
        );
        SpecialistHandoffRequest<?, ?> captured = handoff.getValue();
        assertThat(captured.successorSpecialistId())
            .isEqualTo(AccountResolverSpecialists.READ_SPECIALIST_ID);
        assertThat(captured.successorInput())
            .isEqualTo(new AccountResolutionRequest(request.question()));
        assertThat(captured.trustedExecutionContext())
            .isSameAs(intakeInvocation.getValue()
                .trustedExecutionContext());
        assertThat(captured.idempotencyKey()).isEqualTo("handoff-read-1");
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void billingHandoffUsesOnlyApplicationSuppliedTypedFields() {
        submitted(predecessorResult(
            AccountHandoffDecision.Decision.HANDOFF,
            AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID
        ));
        AccountHandoffIntakeRequest request =
            new AccountHandoffIntakeRequest(
                "Assess this refund against policy.",
                RefundRequest.ResolutionType.REFUND,
                new BigDecimal("75.00")
            );

        service.handoffAccountResolution(
            "session-1",
            request,
            "handoff-billing-1"
        );

        ArgumentCaptor<SpecialistHandoffRequest> handoff =
            ArgumentCaptor.forClass(SpecialistHandoffRequest.class);
        verify(handoffGateway).handoff(
            handoff.capture(),
            eq(BillingResolutionAssessmentRequest.class),
            eq(BillingResolutionAssessmentResult.class)
        );
        assertThat(handoff.getValue().successorInput()).isEqualTo(
            new BillingResolutionAssessmentRequest(
                request.question(),
                request.resolutionType(),
                request.amount()
            )
        );
    }

    @Test
    void completeAndFailedPredecessorsDoNotInvokeASuccessor() {
        submitted(predecessorResult(
            AccountHandoffDecision.Decision.COMPLETE,
            null
        ));

        AccountHandoffResponse complete = service.handoffAccountResolution(
            "session-1",
            new AccountHandoffIntakeRequest(
                "Write a marketing campaign.",
                null,
                null
            ),
            "handoff-complete-1"
        );

        assertThat(complete.predecessor().succeeded()).isTrue();
        assertThat(complete.handoff()).isNull();
        verifyNoInteractions(handoffGateway);

        submitted(failedPredecessor());
        AccountHandoffResponse failed = service.handoffAccountResolution(
            "session-1",
            new AccountHandoffIntakeRequest(
                "Why can I not place an order?",
                null,
                null
            ),
            "handoff-failed-1"
        );

        assertThat(failed.predecessor().status())
            .isEqualTo(AIExecutionStatus.FAILED);
        assertThat(failed.handoff()).isNull();
        verify(handoffGateway, never()).handoff(any(), any(), any());
    }

    @Test
    void requiresIdempotencyBeforeIntakeExecution() {
        assertThatThrownBy(() ->
            service.handoffAccountResolution(
                "session-1",
                new AccountHandoffIntakeRequest(
                    "Why can I not place an order?",
                    null,
                    null
                ),
                " "
            )
        )
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Idempotency-Key is required");

        verifyNoInteractions(intakeClient, handoffGateway);
    }

    private AIExecutionResult<AccountHandoffDecision> predecessorResult(
        AccountHandoffDecision.Decision decision,
        SpecialistId target
    ) {
        return new AIExecutionResult<>(
            "intake-exec-1",
            AccountResolverSpecialists.HANDOFF_INTAKE_ID,
            AIExecutionStatus.SUCCEEDED,
            new AccountHandoffDecision(
                decision,
                target != null ? target.toString() : null,
                "The request matches the declared successor."
            ),
            List.of(),
            Map.of(
                "specialistContentHash",
                "2".repeat(64),
                "executionDeadline",
                NOW.plusSeconds(60).toString()
            ),
            null,
            NOW,
            NOW.plusSeconds(1)
        );
    }

    private AIExecutionResult<AccountHandoffDecision> failedPredecessor() {
        return new AIExecutionResult<>(
            "intake-exec-failed",
            AccountResolverSpecialists.HANDOFF_INTAKE_ID,
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

    private void submitted(AIExecutionResult<AccountHandoffDecision> result) {
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
        when(intakeClient.submit(any(SpecialistInvocation.class)))
            .thenReturn(handle);
        when(intakeClient.find(
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
