package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.action.ActionProposalCoordinator;
import ai.fabric.execution.action.ActionProposalDecision;
import ai.fabric.execution.action.ActionProposalDecisionRequest;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AgenticResolverExecutionService service(
        UUID subject,
        AtomicReference<AIExecutionRequest<?>> observed
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
            clientFactory(observed),
            mock(ActionProposalCoordinator.class),
            sessions,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SpecialistClientFactory clientFactory(
        AtomicReference<AIExecutionRequest<?>> observed
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
            return client;
        });
        return factory;
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
