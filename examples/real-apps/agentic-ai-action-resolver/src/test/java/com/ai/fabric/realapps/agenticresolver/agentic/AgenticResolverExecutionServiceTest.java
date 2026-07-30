package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecision;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionResumeResult;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.PlanExecutionRequest;
import ai.fabric.execution.plan.PlanExecutionResumeRequest;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import ai.fabric.execution.specialist.client.SpecialistResumeInvocation;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountBillingResolutionPlanRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.AccountResolverPlans;
import com.ai.fabric.realapps.agenticresolver.agentic.plan.PlanInputResumeRequest;
import com.ai.fabric.realapps.agenticresolver.entity.RefundRequest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgenticResolverExecutionServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

    @Test
    void applicationEvaluationUsesServerSubjectAndNoConversation() {
        UUID selectedSubject = UUID.randomUUID();
        AtomicReference<AIExecutionRequest<?>> observed = new AtomicReference<>();
        AgenticResolverExecutionService service = service(
            selectedSubject,
            observed
        );

        service.evaluate(
            "session-1",
            new AccountResolutionRequest(
                "Inspect account 00000000-0000-0000-0000-000000000099"
            )
        );

        AIExecutionRequest<?> request = observed.get();
        assertThat(request.specialistId())
            .isEqualTo(
                AccountResolverSpecialists.READ_SPECIALIST_ID
            );
        assertThat(request.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.APPLICATION);
        assertThat(request.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());
        assertThat(request.conversationBinding()).isNull();
        assertThat(request.trustedExecutionContext().grantedScopes())
            .containsExactlyInAnyOrder(
                "specialist:account-resolver-read@1",
                "action:get_account_profile",
                "vector:account-resolution-policy"
            );
    }

    @Test
    void interactiveExecutionBindsBackendConversation() {
        UUID selectedSubject = UUID.randomUUID();
        AtomicReference<AIExecutionRequest<?>> observed = new AtomicReference<>();
        AgenticResolverExecutionService service = service(
            selectedSubject,
            observed
        );

        service.chat(
            "session-1",
            new AccountResolutionRequest("Why am I blocked?"),
            "browser-request-42"
        );

        AIExecutionRequest<?> request = observed.get();
        assertThat(request.specialistId())
            .isEqualTo(AccountResolverSpecialists.SPECIALIST_ID);
        assertThat(request.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.INTERACTIVE);
        assertThat(request.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());
        assertThat(request.conversationBinding().userId())
            .isEqualTo("demo:session-1:missing-payment");
        assertThat(request.conversationBinding().conversationId())
            .isEqualTo("agentic-chat:session-1:missing-payment");
        assertThat(request.idempotencyKey()).isEqualTo("browser-request-42");
        assertThat(request.trustedExecutionContext().grantedScopes())
            .containsExactlyInAnyOrder(
                "specialist:account-resolver@1",
                "action:get_account_profile",
                "action:update_address",
                "vector:account-resolution-policy"
            );
    }

    @Test
    void billingAssessmentUsesServerSubjectAndReadOnlyScopes() {
        UUID selectedSubject = UUID.randomUUID();
        AtomicReference<AIExecutionRequest<?>> observed =
            new AtomicReference<>();
        AgenticResolverExecutionService service = service(
            selectedSubject,
            observed
        );

        service.assessBillingResolution(
            "session-1",
            new BillingResolutionAssessmentRequest(
                "Assess the current refund policy.",
                RefundRequest.ResolutionType.REFUND,
                null
            ),
            "billing-request-1"
        );

        AIExecutionRequest<?> request = observed.get();
        assertThat(request.specialistId())
            .isEqualTo(
                AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID
            );
        assertThat(request.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.APPLICATION);
        assertThat(request.trustedExecutionContext().initiator().principalId())
            .isEqualTo("agentic-account-resolver");
        assertThat(request.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());
        assertThat(request.conversationBinding()).isNull();
        assertThat(request.idempotencyKey()).isEqualTo("billing-request-1");
        assertThat(request.trustedExecutionContext().grantedScopes())
            .containsExactlyInAnyOrder(
                "specialist:billing-resolution-advisor@1",
                "action:assess_billing_resolution",
                "vector:account-resolution-policy"
            );
        assertThat(request.trustedExecutionContext().grantedScopes())
            .noneMatch(scope -> scope.startsWith("action:update_"))
            .noneMatch(scope -> scope.startsWith("action:request_"));
    }

    @Test
    void billingResumeUsesCurrentBackendIdentityAndTypedResponse() {
        UUID selectedSubject = UUID.randomUUID();
        AtomicReference<AIExecutionRequest<?>> observed =
            new AtomicReference<>();
        AtomicReference<SpecialistResumeInvocation> observedResume =
            new AtomicReference<>();
        AgenticResolverExecutionService service = service(
            selectedSubject,
            observed,
            observedResume
        );
        BillingAmountResponse response =
            new BillingAmountResponse(new BigDecimal("75.00"));

        service.resumeBillingAssessment(
            "session-1",
            new BillingAssessmentResumeRequest(
                "invocation-1",
                "request-1",
                response
            ),
            "resume-key-1"
        );

        SpecialistResumeInvocation invocation = observedResume.get();
        assertThat(invocation.invocationId()).isEqualTo("invocation-1");
        assertThat(invocation.requestId()).isEqualTo("request-1");
        assertThat(invocation.response()).isEqualTo(response);
        assertThat(invocation.idempotencyKey()).isEqualTo("resume-key-1");
        assertThat(invocation.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.APPLICATION);
        assertThat(invocation.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());
        assertThat(invocation.trustedExecutionContext().grantedScopes())
            .containsExactlyInAnyOrder(
                "specialist:billing-resolution-advisor@1",
                "action:assess_billing_resolution",
                "vector:account-resolution-policy"
            );
    }

    @Test
    void billingResumeRejectsMissingIdempotencyKeyBeforeGatewayCall() {
        AtomicReference<SpecialistResumeInvocation> observedResume =
            new AtomicReference<>();
        AgenticResolverExecutionService service = service(
            UUID.randomUUID(),
            new AtomicReference<>(),
            observedResume
        );

        assertThatThrownBy(() -> service.resumeBillingAssessment(
                "session-1",
                new BillingAssessmentResumeRequest(
                    "invocation-1",
                    "request-1",
                    new BillingAmountResponse(new BigDecimal("25.00"))
                ),
                " "
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Idempotency-Key is required");
        assertThat(observedResume).hasValue(null);
    }

    @Test
    void rejectsOversizedIdempotencyKeyBeforeProviderExecution() {
        AtomicReference<AIExecutionRequest<?>> observed = new AtomicReference<>();
        AgenticResolverExecutionService service = service(
            UUID.randomUUID(),
            observed
        );

        assertThatThrownBy(() -> service.chat(
                "session-1",
                new AccountResolutionRequest("Update my address"),
                "x".repeat(201)
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Idempotency-Key");
        assertThat(observed).hasValue(null);
    }

    @Test
    void decisionUsesCurrentBackendOwnedInteractiveIdentity() {
        UUID selectedSubject = UUID.randomUUID();
        SpecialistClientFactory clientFactory = clientFactory(
            new AtomicReference<>()
        );
        ActionProposalCoordinator coordinator =
            mock(ActionProposalCoordinator.class);
        AgenticResolverSessionService sessions =
            mock(AgenticResolverSessionService.class);
        when(sessions.active("session-1")).thenReturn(activeSession(
            selectedSubject
        ));
        AgenticResolverExecutionService service =
            new AgenticResolverExecutionService(
                clientFactory,
                mock(AIInteractiveExecutionGateway.class),
                mock(AIExecutionCoordinator.class),
                coordinator,
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
        ActionProposalDecisionRequest decision =
            new ActionProposalDecisionRequest(
                "receipt-1",
                ActionProposalDecision.CONFIRM
            );

        service.decide("session-1", decision);

        verify(coordinator).decide(
            org.mockito.ArgumentMatchers.eq(decision),
            org.mockito.ArgumentMatchers.argThat(context ->
                interactiveContextMatches(context, selectedSubject)
            )
        );
    }

    @Test
    void billingPlanUsesCombinedReadOnlyScopesAndServerOwnedSubject() {
        UUID selectedSubject = UUID.randomUUID();
        AIExecutionCoordinator coordinator =
            mock(AIExecutionCoordinator.class);
        AgenticResolverSessionService sessions =
            mock(AgenticResolverSessionService.class);
        when(sessions.active("session-1")).thenReturn(activeSession(
            selectedSubject
        ));
        AgenticResolverExecutionService service =
            new AgenticResolverExecutionService(
                clientFactory(new AtomicReference<>()),
                mock(AIInteractiveExecutionGateway.class),
                coordinator,
                mock(ActionProposalCoordinator.class),
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
        AccountBillingResolutionPlanRequest request =
            new AccountBillingResolutionPlanRequest(
                "Assess this refund.",
                RefundRequest.ResolutionType.REFUND,
                new BigDecimal("75.00")
            );

        service.executeAccountBillingPlan(
            "session-1",
            request,
            "plan-request-1"
        );

        ArgumentCaptor<PlanExecutionRequest<?>> captured =
            planRequestCaptor();
        verify(coordinator).execute(captured.capture());
        PlanExecutionRequest<?> invocation = captured.getValue();
        assertThat(invocation.planId())
            .isEqualTo(AccountResolverPlans.ACCOUNT_BILLING_RESOLUTION);
        assertThat(invocation.input()).isEqualTo(request);
        assertThat(invocation.idempotencyKey())
            .isEqualTo("plan-request-1");
        assertThat(invocation.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());
        assertThat(invocation.trustedExecutionContext().grantedScopes())
            .containsExactlyInAnyOrder(
                "specialist:account-resolver-read@1",
                "specialist:billing-resolution-advisor@1",
                "action:get_account_profile",
                "action:assess_billing_resolution",
                "vector:account-resolution-policy"
            )
            .noneMatch(scope -> scope.startsWith("action:update_"))
            .noneMatch(scope -> scope.startsWith("action:request_"));
    }

    @Test
    void independentControlAndParallelRoutesUseTheSameTrustedInput() {
        UUID selectedSubject = UUID.randomUUID();
        AIExecutionCoordinator coordinator =
            mock(AIExecutionCoordinator.class);
        AgenticResolverSessionService sessions =
            mock(AgenticResolverSessionService.class);
        when(sessions.active("session-1")).thenReturn(activeSession(
            selectedSubject
        ));
        AgenticResolverExecutionService service =
            new AgenticResolverExecutionService(
                clientFactory(new AtomicReference<>()),
                mock(AIInteractiveExecutionGateway.class),
                coordinator,
                mock(ActionProposalCoordinator.class),
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
        AccountBillingResolutionPlanRequest request =
            new AccountBillingResolutionPlanRequest(
                "Assess this account credit.",
                RefundRequest.ResolutionType.ACCOUNT_CREDIT,
                new BigDecimal("25.00")
            );

        service.executeIndependentSequentialBillingPlan(
            "session-1",
            request,
            "sequential-1"
        );
        service.executeIndependentParallelBillingPlan(
            "session-1",
            request,
            "parallel-1"
        );

        ArgumentCaptor<PlanExecutionRequest<?>> captured =
            planRequestCaptor();
        verify(coordinator, times(2)).execute(captured.capture());
        assertThat(captured.getAllValues())
            .extracting(PlanExecutionRequest::planId)
            .containsExactly(
                AccountResolverPlans
                    .ACCOUNT_BILLING_INDEPENDENT_SEQUENTIAL,
                AccountResolverPlans
                    .ACCOUNT_BILLING_INDEPENDENT_PARALLEL
            );
        assertThat(captured.getAllValues())
            .extracting(PlanExecutionRequest::input)
            .allSatisfy(input -> assertThat(input).isEqualTo(request));
        assertThat(captured.getAllValues())
            .extracting(PlanExecutionRequest::idempotencyKey)
            .containsExactly("sequential-1", "parallel-1");
        assertThat(captured.getAllValues())
            .allSatisfy(invocation -> {
                assertThat(
                    invocation.trustedExecutionContext()
                        .subject().subjectId()
                ).isEqualTo(selectedSubject.toString());
                assertThat(
                    invocation.trustedExecutionContext().grantedScopes()
                ).containsExactlyInAnyOrder(
                    "specialist:account-resolver-read@1",
                    "specialist:billing-resolution-advisor@1",
                    "action:get_account_profile",
                    "action:assess_billing_resolution",
                    "vector:account-resolution-policy"
                );
            });
    }

    @Test
    void planResumeUsesCurrentBackendContextAndRequiredIdempotency() {
        UUID selectedSubject = UUID.randomUUID();
        AIExecutionCoordinator coordinator =
            mock(AIExecutionCoordinator.class);
        AgenticResolverSessionService sessions =
            mock(AgenticResolverSessionService.class);
        when(sessions.active("session-1")).thenReturn(activeSession(
            selectedSubject
        ));
        AgenticResolverExecutionService service =
            new AgenticResolverExecutionService(
                clientFactory(new AtomicReference<>()),
                mock(AIInteractiveExecutionGateway.class),
                coordinator,
                mock(ActionProposalCoordinator.class),
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
        var response = new BillingAmountResponse(
            new java.math.BigDecimal("75")
        );

        service.resumeAccountBillingPlan(
            "session-1",
            new PlanInputResumeRequest(
                "plan-execution-1",
                "amount-request-1",
                response
            ),
            "plan-resume-1"
        );

        ArgumentCaptor<PlanExecutionResumeRequest> captured =
            ArgumentCaptor.forClass(PlanExecutionResumeRequest.class);
        verify(coordinator).resume(captured.capture());
        PlanExecutionResumeRequest invocation = captured.getValue();
        assertThat(invocation.executionId())
            .isEqualTo("plan-execution-1");
        assertThat(invocation.requestId()).isEqualTo("amount-request-1");
        assertThat(invocation.response()).isEqualTo(response);
        assertThat(invocation.idempotencyKey())
            .isEqualTo("plan-resume-1");
        assertThat(invocation.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());

        assertThatThrownBy(() -> service.resumeAccountBillingPlan(
                "session-1",
                new PlanInputResumeRequest(
                    "plan-execution-1",
                    "amount-request-1",
                    response
                ),
                " "
            ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Idempotency-Key is required");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AgenticResolverExecutionService service(
        UUID subject,
        AtomicReference<AIExecutionRequest<?>> observed
    ) {
        return service(subject, observed, new AtomicReference<>());
    }

    private AgenticResolverExecutionService service(
        UUID subject,
        AtomicReference<AIExecutionRequest<?>> observed,
        AtomicReference<SpecialistResumeInvocation> observedResume
    ) {
        AgenticResolverSessionService sessions =
            mock(AgenticResolverSessionService.class);
        when(sessions.active("session-1")).thenReturn(
            new AgenticResolverSessionService.ActiveSession(
                "session-1",
                "missing-payment",
                subject,
                "demo:session-1:missing-payment",
                "agentic-chat:session-1:missing-payment"
            )
        );
        return new AgenticResolverExecutionService(
            clientFactory(observed, observedResume),
            interactiveGateway(observed),
            mock(AIExecutionCoordinator.class),
            mock(ActionProposalCoordinator.class),
            sessions,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AIInteractiveExecutionGateway interactiveGateway(
        AtomicReference<AIExecutionRequest<?>> observed
    ) {
        AIInteractiveExecutionGateway gateway =
            mock(AIInteractiveExecutionGateway.class);
        when(gateway.execute(any(AIExecutionRequest.class)))
            .thenAnswer(execution -> {
                AIExecutionRequest<AccountResolutionRequest> request =
                    execution.getArgument(0);
                observed.set(request);
                return new AIExecutionResult<>(
                    "interactive-exec-1",
                    request.specialistId(),
                    AIExecutionStatus.SUCCEEDED,
                    new AccountResolutionResult(
                        AccountResolutionResult.Assessment.READY,
                        "Ready",
                        List.of()
                    ),
                    List.of(),
                    Map.of(),
                    null,
                    NOW,
                    NOW
                );
            });
        return gateway;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SpecialistClientFactory clientFactory(
        AtomicReference<AIExecutionRequest<?>> observed
    ) {
        return clientFactory(observed, new AtomicReference<>());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SpecialistClientFactory clientFactory(
        AtomicReference<AIExecutionRequest<?>> observed,
        AtomicReference<SpecialistResumeInvocation> observedResume
    ) {
        SpecialistClientFactory factory = mock(SpecialistClientFactory.class);
        when(factory.bind(
            any(SpecialistId.class),
            org.mockito.ArgumentMatchers.eq(AccountResolutionRequest.class),
            org.mockito.ArgumentMatchers.eq(AccountResolutionResult.class)
        )).thenAnswer(binding -> {
            SpecialistId specialistId = binding.getArgument(0);
            SpecialistClient client = mock(SpecialistClient.class);
            when(client.execute(any(SpecialistInvocation.class)))
                .thenAnswer(execution -> {
                    SpecialistInvocation<AccountResolutionRequest> invocation =
                        execution.getArgument(0);
                    observed.set(new AIExecutionRequest<>(
                        specialistId,
                        invocation.input(),
                        invocation.trustedExecutionContext(),
                        invocation.conversationBinding(),
                        invocation.deadline(),
                        invocation.idempotencyKey()
                    ));
                    return new AIExecutionResult<>(
                        "exec-1",
                        specialistId,
                        AIExecutionStatus.SUCCEEDED,
                        new AccountResolutionResult(
                            AccountResolutionResult.Assessment.READY,
                            "Ready",
                            List.of()
                        ),
                        List.of(),
                        Map.of(),
                        null,
                        NOW,
                        NOW
                    );
                });
            when(client.executeInteractive(
                any(SpecialistInvocation.class),
                any(AIInteractiveExecutionGateway.class)
            )).thenAnswer(execution -> {
                SpecialistInvocation<AccountResolutionRequest> invocation =
                    execution.getArgument(0);
                observed.set(new AIExecutionRequest<>(
                    specialistId,
                    invocation.input(),
                    invocation.trustedExecutionContext(),
                    invocation.conversationBinding(),
                    invocation.deadline(),
                    invocation.idempotencyKey()
                ));
                return new AIExecutionResult<>(
                    "interactive-exec-1",
                    specialistId,
                    AIExecutionStatus.SUCCEEDED,
                    new AccountResolutionResult(
                        AccountResolutionResult.Assessment.READY,
                        "Ready",
                        List.of()
                    ),
                    List.of(),
                    Map.of(),
                    null,
                    NOW,
                    NOW
                );
            });
            return client;
        });
        when(factory.bind(
            eq(AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID),
            eq(BillingResolutionAssessmentRequest.class),
            eq(BillingResolutionAssessmentResult.class)
        )).thenAnswer(binding -> {
            SpecialistClient client = mock(SpecialistClient.class);
            when(client.execute(any(SpecialistInvocation.class)))
                .thenAnswer(execution -> {
                    SpecialistInvocation<
                        BillingResolutionAssessmentRequest
                    > invocation = execution.getArgument(0);
                    observed.set(new AIExecutionRequest<>(
                        AccountResolverSpecialists
                            .BILLING_ADVISOR_SPECIALIST_ID,
                        invocation.input(),
                        invocation.trustedExecutionContext(),
                        invocation.conversationBinding(),
                        invocation.deadline(),
                        invocation.idempotencyKey()
                    ));
                    return billingResult();
                });
            when(client.resume(any(SpecialistResumeInvocation.class)))
                .thenAnswer(resume -> {
                    SpecialistResumeInvocation invocation =
                        resume.getArgument(0);
                    observedResume.set(invocation);
                    return AIExecutionResumeResult.resumed(billingResult());
                });
            return client;
        });
        return factory;
    }

    private AIExecutionResult<BillingResolutionAssessmentResult>
    billingResult() {
        return new AIExecutionResult<>(
            "billing-exec-1",
            AccountResolverSpecialists.BILLING_ADVISOR_SPECIALIST_ID,
            AIExecutionStatus.SUCCEEDED,
            new BillingResolutionAssessmentResult(
                RefundRequest.ResolutionType.REFUND,
                new BigDecimal("25.00"),
                BillingResolutionAssessmentResult.Decision.AUTO_APPROVED,
                BillingResolutionAssessmentResult.ExpectedStatus.APPROVED,
                new BigDecimal("50.00"),
                "Within the automatic refund limit."
            ),
            List.of(),
            Map.of(),
            null,
            NOW,
            NOW
        );
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ArgumentCaptor<PlanExecutionRequest<?>> planRequestCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(
            PlanExecutionRequest.class
        );
    }

    private AgenticResolverSessionService.ActiveSession activeSession(
        UUID subject
    ) {
        return new AgenticResolverSessionService.ActiveSession(
            "session-1",
            "missing-payment",
            subject,
            "demo:session-1:missing-payment",
            "agentic-chat:session-1:missing-payment"
        );
    }

    private boolean interactiveContextMatches(
        TrustedExecutionContext context,
        UUID selectedSubject
    ) {
        return context.source() == ExecutionSource.INTERACTIVE
            && context.initiator().principalId().equals(
                "demo:session-1:missing-payment"
            )
            && context.subject().subjectId().equals(selectedSubject.toString())
            && context.grantedScopes().contains("action:update_address");
    }
}
