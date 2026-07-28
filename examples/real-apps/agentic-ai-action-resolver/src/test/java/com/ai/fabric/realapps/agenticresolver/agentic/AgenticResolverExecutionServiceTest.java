package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.gateway.AIExecutionResult;
import ai.fabric.execution.gateway.AIExecutionStatus;
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
        assertThat(request.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.APPLICATION);
        assertThat(request.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());
        assertThat(request.conversationBinding()).isNull();
        assertThat(request.trustedExecutionContext().grantedScopes())
            .containsExactlyInAnyOrder(
                "specialist:account-resolver@1",
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
            new AccountResolutionRequest("Why am I blocked?")
        );

        AIExecutionRequest<?> request = observed.get();
        assertThat(request.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.INTERACTIVE);
        assertThat(request.trustedExecutionContext().subject().subjectId())
            .isEqualTo(selectedSubject.toString());
        assertThat(request.conversationBinding().userId())
            .isEqualTo("demo:session-1:missing-payment");
        assertThat(request.conversationBinding().conversationId())
            .isEqualTo("agentic-chat:session-1:missing-payment");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private AgenticResolverExecutionService service(
        UUID subject,
        AtomicReference<AIExecutionRequest<?>> observed
    ) {
        AIExecutionGateway gateway = mock(AIExecutionGateway.class);
        when(gateway.execute(any())).thenAnswer(invocation -> {
            observed.set(invocation.getArgument(0));
            return new AIExecutionResult<>(
                "exec-1",
                AccountResolverSpecialistConfiguration.SPECIALIST_ID,
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
            gateway,
            sessions,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }
}
