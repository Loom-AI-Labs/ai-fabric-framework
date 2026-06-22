package ai.fabric.behavior.it.realapi;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.it.BehaviorIntegrationTestApp;
import ai.fabric.behavior.it.support.TestEventProvider;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.ExternalEvent;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = BehaviorIntegrationTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.allow-bean-definition-overriding=true",
        "ai.behavior.processing.api-enabled=true",
        "ai.behavior.processing.api-max-batch-size=5"
    }
)
@AutoConfigureTestRestTemplate
@ActiveProfiles("integration")
class BehaviorProcessingRealApiIT {

    private static final Logger log = LoggerFactory.getLogger(BehaviorProcessingRealApiIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private TestEventProvider eventProvider;

    @Autowired
    private BehaviorInsightsRepository repository;

    @MockitoBean
    private AICoreService aiCoreService;

    @BeforeEach
    void setup() {
        repository.deleteAll();
        eventProvider.clear();
    }

    @Test
    void analyzeUser_viaApi_persistsSentimentChurnTrend() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        eventProvider.setTargetedEvents(java.util.List.of(
            ExternalEvent.builder()
                .eventType("login")
                .timestamp(LocalDateTime.now())
                .eventData(Map.of("device", "ios"))
                .source("app")
                .build()
        ));

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "Mobile",
                      "patterns": ["login"],
                      "sentiment": {"score": 0.7, "label": "SATISFIED"},
                      "churn": {"risk": 0.1, "reason": "good ux"},
                      "trend": "IMPROVING",
                      "recommendations": ["nps"],
                      "insights": {"device":"ios"},
                      "confidence": 0.8
                    }
                    """)
                .model("stub-model")
                .build()
        );

        ResponseEntity<BehaviorInsights> response = restTemplate.postForEntity(
            "/api/behavior/processing/users/" + userId,
            null,
            BehaviorInsights.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        BehaviorInsights body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.getSentimentLabel()).isEqualTo(SentimentLabel.SATISFIED);
        assertThat(body.getChurnRisk()).isEqualTo(0.1);
        assertThat(body.getTrend()).isEqualTo(BehaviorTrend.IMPROVING);
        assertThat(body.getRecommendations()).contains("nps");
        log.info("analyzeUser API response: {}", body);

        BehaviorInsights persisted = repository.findByUserId(userId).orElseThrow();
        assertThat(persisted.getSentimentLabel()).isEqualTo(SentimentLabel.SATISFIED);
        assertThat(persisted.getChurnRisk()).isEqualTo(0.1);
        log.info("analyzeUser realapi insight: {}", persisted);
    }

    @Test
    void batchProcessing_viaApi_processesNextUserWithContext() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        eventProvider.setNextBatch(
            userId,
            java.util.List.of(
                ExternalEvent.builder()
                    .eventType("upgrade")
                    .timestamp(LocalDateTime.now())
                    .eventData(Map.of("plan", "pro"))
                    .source("web")
                    .build()
            ),
            Map.of("tier", "gold")
        );

        when(aiCoreService.generateContent(ArgumentMatchers.any())).thenReturn(
            AIGenerationResponse.builder()
                .content("""
                    {
                      "segment": "Pro",
                      "patterns": ["upgrade"],
                      "sentiment": {"score": 0.85, "label": "DELIGHTED"},
                      "churn": {"risk": 0.05, "reason": "positive signals"},
                      "trend": "RAPIDLY_IMPROVING",
                      "recommendations": ["reward"],
                      "insights": {"tier":"gold"},
                      "confidence": 0.9
                    }
                    """)
                .model("stub-model")
                .build()
        );

        String body = "{\"maxUsers\":2,\"maxDurationMinutes\":1}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/behavior/processing/batch",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).containsKey("processedCount");
        log.info("batch API response: {}", response.getBody());

        BehaviorInsights persisted = repository.findByUserId(userId).orElse(null);
        assertThat(persisted).isNotNull();
        assertThat(persisted.getSentimentLabel()).isEqualTo(SentimentLabel.DELIGHTED);
        assertThat(persisted.getChurnRisk()).isEqualTo(0.05);
        assertThat(persisted.getTrend()).isEqualTo(BehaviorTrend.RAPIDLY_IMPROVING);
        log.info("batch realapi insight: {}", persisted);
    }
}
