package com.ai.fabric.realapps.agenticresolver.agentic.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.gateway.ExecutionHandle;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.specialist.client.SpecialistClient;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import ai.fabric.execution.specialist.client.SpecialistInvocation;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionRequest;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialists;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProactiveAccountEventServiceTest {

    private static final Instant NOW =
        Instant.parse("2026-07-29T10:00:00Z");
    private final SpecialistClientFactory clientFactory =
        mock(SpecialistClientFactory.class);
    private final AgenticResolverSessionService sessionService =
        mock(AgenticResolverSessionService.class);
    private final PaymentVerificationFailedEventMapper mapper =
        new PaymentVerificationFailedEventMapper();
    @SuppressWarnings("unchecked")
    private final SpecialistClient<
        AccountResolutionRequest,
        AccountResolutionResult
    > readClient = mock(SpecialistClient.class);
    private final UUID subjectId = UUID.fromString(
        "11111111-2222-3333-4444-555555555555"
    );
    private ProactiveAccountEventService service;

    @BeforeEach
    void setUp() {
        when(clientFactory.bind(
            AccountResolverSpecialists.READ_SPECIALIST_ID,
            AccountResolutionRequest.class,
            AccountResolutionResult.class
        )).thenReturn(readClient);
        when(sessionService.active("session-1")).thenReturn(
            new AgenticResolverSessionService.ActiveSession(
                "session-1",
                "missing-payment",
                subjectId,
                "owner-1",
                "conversation-1"
            )
        );
        service = new ProactiveAccountEventService(
            clientFactory,
            sessionService,
            mapper,
            Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void submitsRawEventWithServiceOwnedEventContextAndStableKey() {
        PaymentVerificationFailedEvent event = event(
            "payment-event-42",
            PaymentVerificationFailureCode.DECLINED,
            2
        );
        ExecutionHandle handle = handle(
            "exec-event-42",
            ExecutionHandleStatus.QUEUED
        );
        when(readClient.submit(
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(handle);

        ProactiveEventSubmission submission = service.submit(
            "session-1",
            event
        );

        ArgumentCaptor<SpecialistInvocation<AccountResolutionRequest>>
            invocation = ArgumentCaptor.forClass(
                SpecialistInvocation.class
            );
        verify(readClient).submit(invocation.capture());
        var actual = invocation.getValue();
        assertThat(submission.eventId()).isEqualTo("payment-event-42");
        assertThat(submission.eventType()).isEqualTo(
            ProactiveAccountEventService.EVENT_TYPE
        );
        assertThat(submission.execution()).isSameAs(handle);
        assertThat(actual.input().question())
            .contains("DECLINED")
            .contains("attempt 2");
        assertThat(actual.conversationBinding()).isNull();
        assertThat(actual.idempotencyKey())
            .isEqualTo(
                "account-payment-verification-failed:v1:payment-event-42"
            );
        assertThat(actual.trustedExecutionContext().source())
            .isEqualTo(ExecutionSource.EVENT);
        assertThat(actual.trustedExecutionContext().initiator().principalType())
            .isEqualTo(ExecutionPrincipalType.SERVICE);
        assertThat(actual.trustedExecutionContext().initiator().principalId())
            .isEqualTo("agentic-account-resolver-event-consumer");
        assertThat(actual.trustedExecutionContext().subject().subjectId())
            .isEqualTo(subjectId.toString());
        assertThat(actual.trustedExecutionContext().tenantId())
            .isEqualTo("public-demo");
        assertThat(actual.trustedExecutionContext().correlationId())
            .isEqualTo("payment-event:payment-event-42");
        assertThat(actual.trustedExecutionContext().grantedScopes())
            .containsExactlyInAnyOrder(
                "specialist:account-resolver-read@1",
                "action:get_account_profile",
                "vector:account-resolution-policy"
            );
    }

    @Test
    void usesTheSameEventKeyWhileChangedFactsRemainInThePayload() {
        when(readClient.submit(
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(
            handle("exec-1", ExecutionHandleStatus.SUCCEEDED),
            handle("exec-conflict", ExecutionHandleStatus.REJECTED)
        );

        service.submit(
            "session-1",
            event(
                "same-event",
                PaymentVerificationFailureCode.DECLINED,
                1
            )
        );
        service.submit(
            "session-1",
            event(
                "same-event",
                PaymentVerificationFailureCode.EXPIRED,
                2
            )
        );

        @SuppressWarnings("unchecked")
        ArgumentCaptor<SpecialistInvocation<AccountResolutionRequest>>
            invocation = ArgumentCaptor.forClass(
                SpecialistInvocation.class
            );
        verify(readClient, org.mockito.Mockito.times(2))
            .submit(invocation.capture());
        assertThat(invocation.getAllValues())
            .extracting(SpecialistInvocation::idempotencyKey)
            .containsOnly(
                "account-payment-verification-failed:v1:same-event"
            );
        assertThat(invocation.getAllValues().get(0).input().question())
            .contains("DECLINED");
        assertThat(invocation.getAllValues().get(1).input().question())
            .contains("EXPIRED");
    }

    @Test
    void findsAndCancelsOnlyThroughTheSameEventContext() {
        SpecialistExecutionSnapshot<AccountResolutionResult> snapshot =
            new SpecialistExecutionSnapshot<>(
                handle("exec-1", ExecutionHandleStatus.RUNNING),
                null
            );
        when(readClient.find(
            eq("exec-1"),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(Optional.of(snapshot));
        when(readClient.cancel(
            eq("exec-1"),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(true);

        assertThat(service.find("session-1", "exec-1"))
            .contains(snapshot);
        assertThat(service.cancel("session-1", "exec-1")).isTrue();
    }

    @Test
    void rejectsFutureEventsBeforeSubmittingIntelligence() {
        assertThatThrownBy(() -> service.submit(
            "session-1",
            new PaymentVerificationFailedEvent(
                "future-event",
                PaymentVerificationFailureCode.DECLINED,
                1,
                NOW.plusSeconds(1)
            )
        )).isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("future");
    }

    private PaymentVerificationFailedEvent event(
        String eventId,
        PaymentVerificationFailureCode code,
        int attempt
    ) {
        return new PaymentVerificationFailedEvent(
            eventId,
            code,
            attempt,
            NOW.minusSeconds(60)
        );
    }

    private ExecutionHandle handle(
        String invocationId,
        ExecutionHandleStatus status
    ) {
        return new ExecutionHandle(
            invocationId,
            ExecutionDurability.EPHEMERAL,
            status,
            NOW.plusSeconds(30),
            NOW.plusSeconds(300),
            status == ExecutionHandleStatus.REJECTED
                ? "IDEMPOTENCY_CONFLICT"
                : null
        );
    }
}
