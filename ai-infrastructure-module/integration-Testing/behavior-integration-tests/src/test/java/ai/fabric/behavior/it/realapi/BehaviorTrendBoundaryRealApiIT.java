package ai.fabric.behavior.it.realapi;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.it.BehaviorIntegrationTestApp;
import ai.fabric.behavior.it.support.TestEventProvider;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.service.BehaviorAnalysisService;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = BehaviorIntegrationTestApp.class,
    properties = {
        "spring.main.allow-bean-definition-overriding=true"
    }
)
@ActiveProfiles("integration")
class BehaviorTrendBoundaryRealApiIT {

    @Autowired
    private BehaviorAnalysisService analysisService;

    @Autowired
    private BehaviorInsightsRepository repository;

    @Autowired
    private TestEventProvider eventProvider;

    @MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        eventProvider.clear();
    }

    @Test
    void trendBoundaryRapidlyImprovingWhenSentimentDeltaAbovePointFour() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        BehaviorInsights existing = repository.save(
            BehaviorInsights.builder()
                .userId(userId)
                .sentimentScore(0.0)
                .churnRisk(0.3)
                .trend(BehaviorTrend.STABLE)
                .analyzedAt(LocalDateTime.now().minusDays(1))
                .build()
        );

        eventProvider.setTargetedEvents(List.of(
            ExternalEvent.builder()
                .eventType("praise")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("channel", "support"))
                .source("web")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "Happy",
                      "patterns": ["praise"],
                      "sentiment": {"score": 0.5, "label": "SATISFIED"},
                      "churn": {"risk": 0.1, "reason": "positive feedback"},
                      "trend": "STABLE",
                      "recommendations": ["thank"],
                      "insights": {},
                      "confidence": 0.7
                    }
                    """)
                .model("stub-model")
                .build()
        );

        BehaviorInsights updated = analysisService.analyzeUser(userId);

        assertThat(updated.getTrend()).isEqualTo(BehaviorTrend.RAPIDLY_IMPROVING);
        assertThat(updated.getSentimentDelta()).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(updated.getChurnDelta()).isCloseTo(-0.2, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(updated.getId()).isEqualTo(existing.getId());
    }

    @Test
    void trendBoundaryRapidlyDecliningWhenChurnDeltaAbovePointFour() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        BehaviorInsights existing = repository.save(
            BehaviorInsights.builder()
                .userId(userId)
                .sentimentScore(0.2)
                .churnRisk(0.3)
                .trend(BehaviorTrend.STABLE)
                .analyzedAt(LocalDateTime.now().minusDays(1))
                .build()
        );

        eventProvider.setTargetedEvents(List.of(
            ExternalEvent.builder()
                .eventType("cancel_attempt")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("reason", "price"))
                .source("web")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "AtRisk",
                      "patterns": ["cancel_attempt"],
                      "sentiment": {"score": -0.3, "label": "FRUSTRATED"},
                      "churn": {"risk": 0.9, "reason": "price"},
                      "trend": "STABLE",
                      "recommendations": ["offer_discount"],
                      "insights": {},
                      "confidence": 0.65
                    }
                    """)
                .model("stub-model")
                .build()
        );

        BehaviorInsights updated = analysisService.analyzeUser(userId);

        assertThat(updated.getTrend()).isEqualTo(BehaviorTrend.RAPIDLY_DECLINING);
        assertThat(updated.getSentimentDelta()).isCloseTo(-0.5, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(updated.getChurnDelta()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(updated.getId()).isEqualTo(existing.getId());
    }
}
