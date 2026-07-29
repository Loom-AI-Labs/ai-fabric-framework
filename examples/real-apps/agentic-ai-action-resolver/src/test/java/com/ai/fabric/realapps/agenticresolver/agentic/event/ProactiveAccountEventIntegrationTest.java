package com.ai.fabric.realapps.agenticresolver.agentic.event;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.gateway.AIExecutionStatus;
import ai.fabric.execution.gateway.ExecutionDurability;
import ai.fabric.execution.gateway.ExecutionHandleStatus;
import ai.fabric.execution.specialist.client.SpecialistExecutionSnapshot;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolutionResult;
import com.ai.fabric.realapps.agenticresolver.agentic.AgenticResolverSessionService;
import com.ai.fabric.realapps.agenticresolver.service.AccountResolutionService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:proactive-event-integration;DB_CLOSE_DELAY=-1",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "ai.providers.openai.enabled=false",
    "ai.execution.receipts.encryption-secret=test-event-receipt-encryption-key-at-least-32",
    "ai.execution.receipts.fingerprint-secret=test-event-receipt-fingerprint-key-at-least-32",
    "ai.vector-db.lucene.index-path=target/proactive-event-integration-index",
    "app.demo.cleanup.enabled=false",
    "logging.level.ai.fabric=WARN"
})
class ProactiveAccountEventIntegrationTest {

    private static final Set<ExecutionHandleStatus> TERMINAL =
        EnumSet.of(
            ExecutionHandleStatus.SUCCEEDED,
            ExecutionHandleStatus.FAILED,
            ExecutionHandleStatus.CANCELLED,
            ExecutionHandleStatus.REJECTED,
            ExecutionHandleStatus.EXPIRED
        );

    @Autowired
    private ProactiveAccountEventService eventService;

    @Autowired
    private AgenticResolverSessionService sessionService;

    @Autowired
    private AccountResolutionService accountResolutionService;

    @Autowired
    private Clock clock;

    @Test
    void replaysOneScopedEventRejectsChangedFactsAndNeverMutates()
        throws InterruptedException {
        AgenticResolverSessionService.SessionView owner =
            sessionService.create();
        AgenticResolverSessionService.SessionView other =
            sessionService.create();
        try {
            sessionService.select(
                owner.sessionId(),
                "missing-payment"
            );
            var active = sessionService.active(owner.sessionId());
            var before = accountResolutionService
                .inspectReadinessByUserId(active.subjectUserId());
            Instant occurredAt = clock.instant().minusSeconds(1);
            PaymentVerificationFailedEvent event =
                new PaymentVerificationFailedEvent(
                    "integration-payment-event",
                    PaymentVerificationFailureCode.DECLINED,
                    2,
                    occurredAt
                );

            ProactiveEventSubmission first = eventService.submit(
                owner.sessionId(),
                event
            );
            ProactiveEventSubmission replay = eventService.submit(
                owner.sessionId(),
                event
            );

            assertThat(replay.execution().invocationId())
                .isEqualTo(first.execution().invocationId());
            assertThat(first.execution().durability())
                .isEqualTo(ExecutionDurability.DURABLE);

            ProactiveEventSubmission conflict = eventService.submit(
                owner.sessionId(),
                new PaymentVerificationFailedEvent(
                    event.eventId(),
                    PaymentVerificationFailureCode.EXPIRED,
                    3,
                    occurredAt
                )
            );
            assertThat(conflict.execution().status())
                .isEqualTo(ExecutionHandleStatus.REJECTED);
            assertThat(conflict.execution().failureReason())
                .isEqualTo("IDEMPOTENCY_CONFLICT");

            SpecialistExecutionSnapshot<AccountResolutionResult> terminal =
                awaitTerminal(
                    owner.sessionId(),
                    first.execution().invocationId()
                );
            assertThat(terminal.result()).isNotNull();
            assertThat(terminal.result().status())
                .as("disabled provider failures must remain visible")
                .isNotEqualTo(AIExecutionStatus.SUCCEEDED);
            assertThat(terminal.result().failure()).isNotNull();

            assertThat(eventService.find(
                other.sessionId(),
                first.execution().invocationId()
            )).isEmpty();
            assertThat(accountResolutionService.inspectReadinessByUserId(
                active.subjectUserId()
            )).isEqualTo(before);
        } finally {
            sessionService.delete(owner.sessionId());
            sessionService.delete(other.sessionId());
        }
    }

    private SpecialistExecutionSnapshot<AccountResolutionResult>
    awaitTerminal(
        String sessionId,
        String invocationId
    ) throws InterruptedException {
        Instant timeout = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(timeout)) {
            var snapshot = eventService.find(sessionId, invocationId);
            if (
                snapshot.isPresent()
                    && TERMINAL.contains(snapshot.get().handle().status())
            ) {
                return snapshot.get();
            }
            Thread.sleep(25);
        }
        throw new AssertionError(
            "Proactive event execution did not reach a terminal state"
        );
    }
}
