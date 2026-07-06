package com.ai.fabric.realapps.behavior.service;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.service.BehaviorAnalysisService;
import com.ai.fabric.realapps.behavior.domain.AppBehaviorEvent;
import com.ai.fabric.realapps.behavior.repo.AppBehaviorEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BehaviorDemoScenarioServiceTest {

    private final DemoEventSeeder seeder = mock(DemoEventSeeder.class);
    private final BehaviorAnalysisService behaviorAnalysisService = mock(BehaviorAnalysisService.class);
    private final AppBehaviorEventRepository eventRepository = mock(AppBehaviorEventRepository.class);
    private final BehaviorInsightsRepository insightsRepository = mock(BehaviorInsightsRepository.class);
    private final BehaviorDemoScenarioService service = new BehaviorDemoScenarioService(
        seeder,
        behaviorAnalysisService,
        eventRepository,
        insightsRepository,
        new RetentionStudioService(),
        new ObjectMapper()
    );

    @Test
    void analyzeReturnsBehaviorEvidenceAndConfirmationGatedRetentionOffer() {
        BehaviorInsights insight = BehaviorInsights.builder()
            .userId("user-1001")
            .segment("at_risk")
            .sentimentLabel(SentimentLabel.CHURNING)
            .sentimentScore(-0.74)
            .churnRisk(0.93)
            .churnReason("Repeated renewal failures and cancellation intent")
            .trend(BehaviorTrend.RAPIDLY_DECLINING)
            .patterns(List.of("payment_signals:2", "cancel_signals:2"))
            .recommendations(List.of("Offer retention credit", "Assign CSM outreach"))
            .confidence(0.82)
            .aiModelUsed("behavior-local")
            .processingTimeMs(12L)
            .analyzedAt(LocalDateTime.now())
            .build();
        when(behaviorAnalysisService.analyzeUser("user-1001")).thenReturn(insight);
        when(eventRepository.findByUserIdOrderByEventTimestampAsc("user-1001"))
            .thenReturn(List.of(event("user-1001", "PAYMENT_FAILED"), event("user-1001", "CANCEL_INTENT")));

        BehaviorDemoScenarioService.BehaviorScenarioResult result = service.analyze("user-1001");

        assertThat(result.scenario().customerName()).isEqualTo("Acme Finance");
        assertThat(result.insight().requiresImmediateAction()).isTrue();
        assertThat(result.insight().patterns()).contains("payment_signals:2", "cancel_signals:2");
        assertThat(result.events()).hasSize(2);
        assertThat(result.retentionReview().riskCategory()).isEqualTo("HIGH");
        assertThat(result.retentionReview().evidenceIds()).contains("insight-acct-1001-user-1001", "plan-pro");
        assertThat(result.retentionOfferPreview().result().confirmationRequired()).isTrue();
        assertThat(result.retentionOfferPreview().actionName()).isEqualTo("create_retention_offer");
        verify(behaviorAnalysisService).analyzeUser("user-1001");
    }

    @Test
    void confirmedRetentionOfferExecutesActionPayload() {
        BehaviorDemoScenarioService.RetentionOfferDemoResult result = service.retentionOffer(
            "user-1001",
            new BehaviorDemoScenarioService.RetentionOfferDemoRequest(25, true)
        );

        assertThat(result.result().success()).isTrue();
        assertThat(result.result().confirmationRequired()).isFalse();
        assertThat(result.result().data()).containsAllEntriesOf(Map.of(
            "accountId", "acct-1001",
            "userId", "user-1001",
            "discountPercent", 25
        ));
        assertThat(result.result().data()).containsEntry("policyDecision", "APPROVED");
    }

    @Test
    void createSessionClonesAllScenariosAndAnalyzesSessionUsers() {
        when(eventRepository.findByUserIdOrderByEventTimestampAsc(anyString())).thenReturn(List.of());

        BehaviorDemoScenarioService.DemoSessionResponse response = service.createSession(
            new BehaviorDemoScenarioService.CreateDemoSessionRequest("browser-session-1", true)
        );

        assertThat(response.sessionId()).isEqualTo("browser-session-1");
        assertThat(response.scenarios()).hasSize(5);
        assertThat(response.scenarios())
            .allSatisfy(scenario -> {
                assertThat(scenario.userId()).startsWith(BehaviorDemoScenarioService.SESSION_USER_PREFIX + "browser-session-1-");
                assertThat(scenario.baseUserId()).startsWith("user-");
                assertThat(scenario.expectedActionFamily()).isNotBlank();
            });
        verify(seeder).seedScenario(
            eq("behavior-demo-user-browser-session-1-user-1001"),
            eq("billing-cancellation-risk"),
            eq("demo-session"),
            any(LocalDateTime.class)
        );
        verify(behaviorAnalysisService).analyzeUser("behavior-demo-user-browser-session-1-user-1001");
    }

    @Test
    void recordSignalDefaultsToStructuredRawAppEventData() throws Exception {
        when(eventRepository.findByUserIdOrderByEventTimestampAsc("user-1001")).thenReturn(List.of());

        service.recordSignal("user-1001", new BehaviorDemoScenarioService.RecordBehaviorSignalRequest(null, null, null));

        var eventCaptor = forClass(AppBehaviorEvent.class);
        verify(eventRepository).save(eventCaptor.capture());
        AppBehaviorEvent saved = eventCaptor.getValue();
        assertThat(saved.getEventType()).isEqualTo("PAYMENT_FAILED");
        assertThat(saved.getSource()).isEqualTo("demo-ui");

        @SuppressWarnings("unchecked")
        Map<String, Object> eventData = new ObjectMapper().readValue(saved.getEventData(), Map.class);
        assertThat(eventData).containsAllEntriesOf(Map.of(
            "reason", "card_declined",
            "invoiceStatus", "past_due",
            "renewalAttempt", "2",
            "gateway", "stripe"
        ));
        assertThat(eventData).doesNotContainKey("message");
    }

    @Test
    void resetRequiresExplicitConfirmation() {
        BehaviorDemoScenarioService.ResetResult result = service.reset(
            new BehaviorDemoScenarioService.ResetRequest("browser-session-2", false)
        );

        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("confirm=true");
        assertThat(result.deletedUsers()).isZero();
    }

    @Test
    void resetSessionDeletesOnlyClonedScenarioUsers() {
        BehaviorDemoScenarioService.ResetResult result = service.reset(
            new BehaviorDemoScenarioService.ResetRequest("browser-session-2", true)
        );

        assertThat(result.success()).isTrue();
        assertThat(result.deletedUsers()).isEqualTo(5);
        verify(insightsRepository).deleteByUserId("behavior-demo-user-browser-session-2-user-1001");
        verify(eventRepository).deleteByUserId("behavior-demo-user-browser-session-2-user-1001");
    }

    private static AppBehaviorEvent event(String userId, String eventType) {
        AppBehaviorEvent event = new AppBehaviorEvent();
        event.setId(1L);
        event.setUserId(userId);
        event.setEventType(eventType);
        event.setEventTimestamp(LocalDateTime.now());
        event.setEventData("{}");
        event.setSource("test");
        return event;
    }
}
