package ai.fabric.behavior.it;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.service.BehaviorAnalysisService;
import ai.fabric.behavior.it.support.TestEventProvider;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
class BehaviorAnalysisIntegrationIT {

    @Autowired
    private BehaviorAnalysisService behaviorAnalysisService;

    @Autowired
    private BehaviorInsightsRepository repository;

    @Autowired
    private TestEventProvider eventProvider;

    @MockBean
    private AICoreService aiCoreService; // allow deterministic LLM responses while still exercising full wiring

    @BeforeEach
    void setup() {
        repository.deleteAll();
        eventProvider.clear();
    }

    @AfterEach
    void tearDown() {
        repository.deleteAll();
        eventProvider.clear();
    }

    @Test
    void targetedAnalysis_updatesExistingInsight() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        BehaviorInsights existing = repository.save(
            BehaviorInsights.builder()
                .userId(userId)
                .segment("Old")
                .analyzedAt(LocalDateTime.now().minusDays(1))
                .confidence(0.2)
                .build()
        );

        eventProvider.setTargetedEvents(List.of(
            ExternalEvent.builder()
                .eventType("purchase")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("amount", 99))
                .source("web")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("{\"segment\":\"Power\",\"patterns\":[\"buy\"],\"recommendations\":[\"upsell\"],\"insights\":{},\"confidence\":0.85}")
                .model("stub-model")
                .build()
        );

        BehaviorInsights updated = behaviorAnalysisService.analyzeUser(userId);

        assertThat(updated).isNotNull();
        assertThat(updated.getId()).isEqualTo(existing.getId());
        assertThat(updated.getSegment()).isEqualTo("Power");
        assertThat(updated.getRecommendations()).contains("upsell");
        assertThat(updated.getPatterns()).contains("buy");
        assertThat(updated.getConfidence()).isEqualTo(0.85);
    }

    @Test
    void batchProcessing_usesUserContext() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        eventProvider.setNextBatch(
            userId,
            List.of(
                ExternalEvent.builder()
                    .eventType("login")
                    .timestamp(LocalDateTime.now())
                    .eventData(Map.of("device", "ios"))
                    .source("app")
                    .build()
            ),
            Map.of("tier", "gold")
        );

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("{\"segment\":\"Active\",\"patterns\":[\"login\"],\"recommendations\":[\"notify\"],\"insights\":{\"tier\":\"gold\"},\"confidence\":0.7}")
                .model("stub-model")
                .build()
        );

        BehaviorInsights insight = behaviorAnalysisService.processNextUser();

        assertThat(insight).isNotNull();
        assertThat(insight.getUserId()).isEqualTo(userId);
        assertThat(insight.getSegment()).isEqualTo("Active");
        assertThat(insight.getInsights()).containsEntry("tier", "gold");
    }

    @Test
    void malformedJsonFallsBackToExtractor() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        eventProvider.setTargetedEvents(List.of(
            ExternalEvent.builder()
                .eventType("page_view")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("path", "/home"))
                .source("web")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("LLM RESPONSE -> {\"segment\":\"Browser\",\"patterns\":[\"view\"],\"recommendations\":[\"cta\"],\"insights\":{},\"confidence\":0.5} <- END")
                .model("stub-model")
                .build()
        );

        BehaviorInsights insight = behaviorAnalysisService.analyzeUser(userId);

        assertThat(insight).isNotNull();
        assertThat(insight.getSegment()).isEqualTo("Browser");
        assertThat(insight.getPatterns()).contains("view");
        assertThat(insight.getRecommendations()).contains("cta");
    }

    @Test
    void targetedAnalysis_populatesSentimentChurnAndTrend() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        eventProvider.setTargetedEvents(List.of(
            ExternalEvent.builder()
                .eventType("upgrade")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("plan", "pro"))
                .source("web")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "Pro",
                      "patterns": ["upgrade"],
                      "sentiment": {"score": 0.9, "label": "DELIGHTED"},
                      "churn": {"risk": 0.05, "reason": "happy path"},
                      "trend": "IMPROVING",
                      "recommendations": ["celebrate"],
                      "insights": {"plan": "pro"},
                      "confidence": 0.92
                    }
                    """)
                .model("stub-model")
                .build()
        );

        BehaviorInsights insight = behaviorAnalysisService.analyzeUser(userId);

        assertThat(insight.getSentimentLabel()).isEqualTo(SentimentLabel.DELIGHTED);
        assertThat(insight.getSentimentScore()).isEqualTo(0.9);
        assertThat(insight.getChurnRisk()).isEqualTo(0.05);
        assertThat(insight.getChurnReason()).isEqualTo("happy path");
        assertThat(insight.getTrend()).isEqualTo(BehaviorTrend.IMPROVING);
        assertThat(insight.getRecommendations()).contains("celebrate");
        assertThat(insight.getConfidence()).isEqualTo(0.92);
    }

    @Test
    void trendRecomputedFromDeltasWhenStableReturned() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        BehaviorInsights existing = repository.save(
            BehaviorInsights.builder()
                .userId(userId)
                .sentimentScore(0.4)
                .churnRisk(0.2)
                .trend(BehaviorTrend.STABLE)
                .analyzedAt(LocalDateTime.now().minusDays(1))
                .build()
        );

        eventProvider.setTargetedEvents(List.of(
            ExternalEvent.builder()
                .eventType("downgrade")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("from", "pro", "to", "basic"))
                .source("app")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "AtRisk",
                      "patterns": ["downgrade"],
                      "sentiment": {"score": 0.0, "label": "NEUTRAL"},
                      "churn": {"risk": 0.8, "reason": "downgrade"},
                      "trend": "STABLE",
                      "recommendations": ["retain"],
                      "insights": {},
                      "confidence": 0.6
                    }
                    """)
                .model("stub-model")
                .build()
        );

        BehaviorInsights updated = behaviorAnalysisService.analyzeUser(userId);

        assertThat(updated.getPreviousSentimentScore()).isEqualTo(0.4);
        assertThat(updated.getPreviousChurnRisk()).isEqualTo(0.2);
        assertThat(updated.getSentimentDelta()).isCloseTo(-0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(updated.getChurnDelta()).isCloseTo(0.6, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(updated.getTrend()).isEqualTo(BehaviorTrend.RAPIDLY_DECLINING);
        assertThat(updated.getChurnReason()).isEqualTo("downgrade");
        assertThat(updated.getId()).isEqualTo(existing.getId());
    }
}
