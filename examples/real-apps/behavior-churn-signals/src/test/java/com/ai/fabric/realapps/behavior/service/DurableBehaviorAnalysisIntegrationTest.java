package com.ai.fabric.realapps.behavior.service;

import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService.BehaviorEventConflictException;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService.CreateDemoSessionRequest;
import com.ai.fabric.realapps.behavior.service.BehaviorDemoScenarioService.RecordBehaviorSignalRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "spring.datasource.url=jdbc:h2:mem:behavior_durable_test;MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.sql.init.mode=always",
        "ai.providers.llm-provider=behavior-local",
        "ai.providers.embedding-provider=simple"
    }
)
@ActiveProfiles("smoke")
class DurableBehaviorAnalysisIntegrationTest {

    @Autowired
    private BehaviorDemoScenarioService scenarioService;

    @Autowired
    private DurableBehaviorAnalysisService durableService;

    @Test
    void durableAnalysisReplaysExactFactsRejectsChangedFactsAndEnforcesSessionOwnership()
        throws Exception {
        String requestedSession = "durable-it-" + UUID.randomUUID();
        var session = scenarioService.createSession(
            new CreateDemoSessionRequest(requestedSession, false)
        );
        String sessionId = session.sessionId();
        String userId = session.scenarios().getFirst().userId();

        RecordBehaviorSignalRequest event = new RecordBehaviorSignalRequest(
            "PAYMENT_FAILED",
            Map.of("reason", "card_declined", "attempt", 2),
            "billing-service",
            "billing-event-42"
        );
        var firstEvent = scenarioService.recordEvent(userId, event);
        var replayedEvent = scenarioService.recordEvent(userId, event);

        assertThat(firstEvent.replayed()).isFalse();
        assertThat(replayedEvent.replayed()).isTrue();
        assertThat(replayedEvent.id()).isEqualTo(firstEvent.id());
        assertThatThrownBy(() -> scenarioService.recordEvent(
            userId,
            new RecordBehaviorSignalRequest(
                "PAYMENT_FAILED",
                Map.of("reason", "insufficient_funds", "attempt", 2),
                "billing-service",
                "billing-event-42"
            )
        )).isInstanceOf(BehaviorEventConflictException.class);

        String key = "analysis-" + UUID.randomUUID();
        var first = durableService.submit(sessionId, userId, key);
        var replay = durableService.submit(sessionId, userId, key);

        assertThat(first.durability()).isEqualTo("DURABLE");
        assertThat(first.executionSource()).isEqualTo("APPLICATION");
        assertThat(first.principalType()).isEqualTo("SERVICE");
        assertThat(replay.invocationId()).isEqualTo(first.invocationId());
        assertThat(replay.replayed()).isTrue();

        scenarioService.recordEvent(
            userId,
            new RecordBehaviorSignalRequest(
                "CANCEL_INTENT",
                Map.of("reason", "renewal failed"),
                "support-inbox",
                "cancel-event-43"
            )
        );
        var conflict = durableService.submit(sessionId, userId, key);
        assertThat(conflict.status()).isEqualTo("REJECTED");
        assertThat(conflict.failure().reason()).isEqualTo("IDEMPOTENCY_CONFLICT");

        var completed = awaitTerminal(sessionId, first.invocationId());
        assertThat(completed.status())
            .withFailMessage("Application analysis failed: %s", completed.failure())
            .isEqualTo("SUCCEEDED");
        assertThat(completed.projectionStatus()).isEqualTo("APPLIED");
        assertThat(completed.result()).isNotNull();
        assertThat(completed.result().insight()).isNotNull();
        assertThat(completed.consideredEventCount()).isGreaterThan(0);

        var projectedReplay = durableService.find(sessionId, first.invocationId());
        assertThat(projectedReplay.projectionStatus()).isEqualTo("APPLIED");
        assertThatThrownBy(() -> durableService.find("another-session", first.invocationId()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("does not belong");
    }

    @Test
    void scheduledAnalysisOwnsIdentityAndReplaysTheExactEventBatch()
        throws Exception {
        String requestedSession = "scheduled-it-" + UUID.randomUUID();
        var session = scenarioService.createSession(
            new CreateDemoSessionRequest(requestedSession, false)
        );
        String sessionId = session.sessionId();
        String userId = session.scenarios().getFirst().userId();
        scenarioService.recordEvent(
            userId,
            new RecordBehaviorSignalRequest(
                "PLAN_DOWNGRADED",
                Map.of("from", "PRO", "to", "BASIC"),
                "subscription-service",
                "scheduled-event-" + UUID.randomUUID()
            )
        );

        var first = durableService.submitScheduled(sessionId, userId);
        var replay = durableService.submitScheduled(sessionId, userId);

        assertThat(first.executionSource()).isEqualTo("SCHEDULED");
        assertThat(first.principalType()).isEqualTo("SYSTEM");
        assertThat(first.durability()).isEqualTo("DURABLE");
        assertThat(replay.invocationId()).isEqualTo(first.invocationId());
        assertThat(replay.replayed()).isTrue();

        var completed = awaitTerminal(sessionId, first.invocationId());
        assertThat(completed.status())
            .withFailMessage("Scheduled analysis failed: %s", completed.failure())
            .isEqualTo("SUCCEEDED");
        assertThat(completed.executionSource()).isEqualTo("SCHEDULED");
        assertThat(completed.principalType()).isEqualTo("SYSTEM");
        assertThat(completed.projectionStatus()).isEqualTo("APPLIED");
    }

    private DurableBehaviorAnalysisService.AnalysisView awaitTerminal(
        String sessionId,
        String invocationId
    ) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        DurableBehaviorAnalysisService.AnalysisView current;
        do {
            current = durableService.find(sessionId, invocationId);
            if (!"QUEUED".equals(current.status()) && !"RUNNING".equals(current.status())) {
                return current;
            }
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        throw new AssertionError("Durable behavior analysis did not complete in time");
    }
}
