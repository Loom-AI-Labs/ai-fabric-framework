package com.ai.fabric.realapps.agenticresolver.agentic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.manager.ConversationManagerGateway;
import ai.fabric.execution.manager.ConversationManagerTurnRequest;
import ai.fabric.execution.manager.ConversationManagerTurnResult;
import ai.fabric.execution.manager.ConversationManagerTurnStatus;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AccountConversationManagerServiceTest {

    private static final Instant NOW =
        Instant.parse("2026-07-29T12:00:00Z");

    @Test
    void suppliesOnlyServerOwnedIdentityConversationAndAuthority() {
        ConversationManagerGateway gateway =
            mock(ConversationManagerGateway.class);
        AgenticResolverSessionService sessions =
            mock(AgenticResolverSessionService.class);
        UUID userId = UUID.randomUUID();
        when(sessions.active("session-1")).thenReturn(
            new AgenticResolverSessionService.ActiveSession(
                "session-1",
                "missing-payment",
                userId,
                "demo:session-1:missing-payment",
                "agentic-chat:session-1:missing-payment"
            )
        );
        ConversationManagerTurnResult expected = success();
        when(gateway.execute(any())).thenReturn(expected);
        AccountConversationManagerService service =
            new AccountConversationManagerService(
                gateway,
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );
        AccountDelegationCoordinatorRequest input =
            new AccountDelegationCoordinatorRequest(
                "Assess this refund.",
                com.ai.fabric.realapps.agenticresolver.entity.RefundRequest
                    .ResolutionType.REFUND,
                new BigDecimal("25")
            );

        assertThat(service.chat(
            "session-1",
            input,
            "manager-turn-1"
        )).isSameAs(expected);

        AtomicReference<
            ConversationManagerTurnRequest<
                AccountDelegationCoordinatorRequest
            >
        > captured = new AtomicReference<>();
        verify(gateway)
            .<AccountDelegationCoordinatorRequest>execute(
                argThat(request -> {
                    captured.set(request);
                    return true;
                })
            );
        ConversationManagerTurnRequest<
            AccountDelegationCoordinatorRequest
        > gatewayRequest = captured.get();
        assertThat(gatewayRequest.managerId())
            .isEqualTo(AccountConversationManagers.ACCOUNT_RESOLUTION);
        assertThat(gatewayRequest.input()).isEqualTo(input);
        assertThat(gatewayRequest.idempotencyKey())
            .isEqualTo("manager-turn-1");
        assertThat(gatewayRequest.conversationBinding().userId())
            .isEqualTo("demo:session-1:missing-payment");
        assertThat(gatewayRequest.conversationBinding().conversationId())
            .isEqualTo("agentic-chat:session-1:missing-payment");
        assertThat(gatewayRequest.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.INTERACTIVE);
        assertThat(gatewayRequest.trustedExecutionContext()
            .subject().subjectId()).isEqualTo(userId.toString());
        assertThat(gatewayRequest.trustedExecutionContext()
            .grantedScopes())
            .contains(
                "specialist:account-conversation-manager@1",
                "specialist:account-resolver-manager-read@1",
                "specialist:billing-resolution-manager-advisor@1"
            );
    }

    @Test
    void rejectsMissingIdempotencyBeforeCallingTheGateway() {
        ConversationManagerGateway gateway =
            mock(ConversationManagerGateway.class);
        AgenticResolverSessionService sessions =
            mock(AgenticResolverSessionService.class);
        when(sessions.active("session-1")).thenReturn(
            new AgenticResolverSessionService.ActiveSession(
                "session-1",
                "ready",
                UUID.randomUUID(),
                "owner-1",
                "conversation-1"
            )
        );
        AccountConversationManagerService service =
            new AccountConversationManagerService(
                gateway,
                sessions,
                Clock.fixed(NOW, ZoneOffset.UTC)
            );

        assertThatThrownBy(() -> service.chat(
            "session-1",
            new AccountDelegationCoordinatorRequest(
                "Help me.",
                null,
                null
            ),
            " "
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Idempotency-Key is required");
        verify(gateway, never()).execute(any());
    }

    private ConversationManagerTurnResult success() {
        return new ConversationManagerTurnResult(
            "manager-turn-1",
            AccountConversationManagers.ACCOUNT_RESOLUTION,
            ConversationManagerTurnStatus.COMPLETED,
            "Done.",
            null,
            "manager-invocation-1",
            null,
            "a".repeat(64),
            0,
            null,
            false,
            NOW,
            NOW
        );
    }
}
