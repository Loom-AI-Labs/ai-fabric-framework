package ai.fabric.behavior.it.api;

import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.it.BehaviorIntegrationTestApp;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = BehaviorIntegrationTestApp.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "spring.main.allow-bean-definition-overriding=true"
    }
)
@ActiveProfiles("integration")
class BehaviorAnalyticsApiIT {

    private static final Logger log = LoggerFactory.getLogger(BehaviorAnalyticsApiIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BehaviorInsightsRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void rapidDeclineEndpointReturnsAlerts() {
        BehaviorInsights declining = repository.save(BehaviorInsights.builder()
            .userId("test-user-" + java.util.UUID.randomUUID().toString())
            .sentimentLabel(SentimentLabel.FRUSTRATED)
            .churnRisk(0.9)
            .churnReason("payment failures")
            .trend(BehaviorTrend.RAPIDLY_DECLINING)
            .recommendations(List.of("reach_out"))
            .analyzedAt(LocalDateTime.now())
            .build());

        repository.save(BehaviorInsights.builder()
            .userId("test-user-" + java.util.UUID.randomUUID().toString())
            .sentimentLabel(SentimentLabel.SATISFIED)
            .churnRisk(0.1)
            .trend(BehaviorTrend.IMPROVING)
            .analyzedAt(LocalDateTime.now())
            .build());

        ResponseEntity<List<Map<String, Object>>> response = restTemplate.exchange(
            "/api/behavior/analytics/rapid-decline",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).hasSize(1);
        Map<String, Object> alert = response.getBody().get(0);
        assertThat(alert.get("trend")).isEqualTo("RAPIDLY_DECLINING");
        assertThat(alert.get("churnRisk")).isEqualTo(0.9);
        log.info("rapid-decline alerts: {}", response.getBody());
    }

    @Test
    void trendDistributionAggregatesCounts() {
        repository.save(BehaviorInsights.builder()
            .userId("test-user-" + java.util.UUID.randomUUID().toString())
            .trend(BehaviorTrend.IMPROVING)
            .analyzedAt(LocalDateTime.now())
            .build());
        repository.save(BehaviorInsights.builder()
            .userId("test-user-" + java.util.UUID.randomUUID().toString())
            .trend(BehaviorTrend.IMPROVING)
            .analyzedAt(LocalDateTime.now())
            .build());
        repository.save(BehaviorInsights.builder()
            .userId("test-user-" + java.util.UUID.randomUUID().toString())
            .trend(BehaviorTrend.DECLINING)
            .analyzedAt(LocalDateTime.now())
            .build());

        ResponseEntity<Map<String, Integer>> response = restTemplate.exchange(
            "/api/behavior/analytics/trend-distribution",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Integer> distribution = response.getBody();
        assertThat(distribution.get("IMPROVING")).isEqualTo(2);
        assertThat(distribution.get("DECLINING")).isEqualTo(1);
        log.info("trend distribution: {}", distribution);
    }

    @Test
    void userTrendReturnsDeltas() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        repository.save(BehaviorInsights.builder()
            .userId(userId)
            .sentimentScore(0.6)
            .previousSentimentScore(0.1)
            .churnRisk(0.4)
            .previousChurnRisk(0.2)
            .trend(BehaviorTrend.IMPROVING)
            .churnReason("engagement rising")
            .recommendations(List.of("reward"))
            .analyzedAt(LocalDateTime.now())
            .build());

        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/behavior/analytics/users/" + userId + "/trend",
            HttpMethod.GET,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body.get("trend")).isEqualTo("IMPROVING");
        assertThat(body.get("sentimentDelta")).isEqualTo(0.5);
        assertThat(body.get("churnDelta")).isEqualTo(0.2);
        log.info("user trend response: {}", body);
    }
}
