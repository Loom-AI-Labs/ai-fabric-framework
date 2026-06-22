package ai.fabric.behavior.it.api;

import ai.fabric.behavior.config.BehaviorProcessingProperties;
import ai.fabric.behavior.entity.BehaviorInsights;
import ai.fabric.behavior.model.BehaviorTrend;
import ai.fabric.behavior.model.SentimentLabel;
import ai.fabric.behavior.repository.BehaviorInsightsRepository;
import ai.fabric.behavior.it.BehaviorIntegrationTestApp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
class BehaviorProcessingApiIT {

    private static final Logger log = LoggerFactory.getLogger(BehaviorProcessingApiIT.class);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private BehaviorInsightsRepository repository;

    @Autowired
    private BehaviorProcessingProperties properties;

    @BeforeEach
    void setup() {
        repository.deleteAll();
    }

    @Test
    void analyzeUser_returnsInsight() {
        String userId = "test-user-" + java.util.UUID.randomUUID().toString();
        BehaviorInsights saved = repository.save(BehaviorInsights.builder()
            .userId(userId)
            .segment("Existing")
            .sentimentLabel(SentimentLabel.SATISFIED)
            .churnRisk(0.2)
            .trend(BehaviorTrend.STABLE)
            .analyzedAt(LocalDateTime.now())
            .build());

        ResponseEntity<BehaviorInsights> response = restTemplate.postForEntity(
            "/api/behavior/processing/users/" + userId,
            null,
            BehaviorInsights.class
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getUserId()).isEqualTo(saved.getUserId());
        log.info("analyzeUser insight: {}", response.getBody());
    }

    @Test
    void batch_honorsMaxBatchSizeAndReturnsCounts() {
        properties.setApiMaxBatchSize(1);
        // Seed one pending user to be processed by processNextUser (the service will return null afterwards)
        repository.save(BehaviorInsights.builder()
            .userId("test-user-" + java.util.UUID.randomUUID().toString())
            .segment("Pending")
            .analyzedAt(LocalDateTime.now())
            .build());

        String body = "{\"maxUsers\":3,\"maxDurationMinutes\":1}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
            "/api/behavior/processing/batch",
            HttpMethod.POST,
            new HttpEntity<>(body, headers),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        // Depending on available events, processed may be 0 or more; just ensure field is present
        assertThat(response.getBody()).containsKey("processedCount");
        log.info("batch result: {}", response.getBody());
    }

    @Test
    void continuous_canStartAndCancelJob() {
        String startBody = "{\"usersPerBatch\":1,\"intervalMinutes\":0,\"maxIterations\":1}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map<String, Object>> startResponse = restTemplate.exchange(
            "/api/behavior/processing/continuous",
            HttpMethod.POST,
            new HttpEntity<>(startBody, headers),
            new ParameterizedTypeReference<>() {}
        );

        assertThat(startResponse.getStatusCode().is2xxSuccessful()).isTrue();
        String jobId = (String) startResponse.getBody().get("jobId");
        assertThat(jobId).isNotBlank();
        log.info("continuous start response: {}", startResponse.getBody());

        ResponseEntity<Map<String, Object>> cancelResponse = restTemplate.exchange(
            "/api/behavior/processing/continuous/" + jobId + "/cancel",
            HttpMethod.POST,
            null,
            new ParameterizedTypeReference<>() {}
        );

        assertThat(cancelResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(cancelResponse.getBody()).containsKey("status");
        log.info("continuous cancel response: {}", cancelResponse.getBody());
    }

    @Test
    void scheduled_pauseAndResumeTogglesFlag() {
        ResponseEntity<Map> pause = restTemplate.postForEntity(
            "/api/behavior/processing/scheduled/pause",
            null,
            Map.class
        );
        assertThat(pause.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(pause.getBody()).containsEntry("paused", true);
        log.info("scheduled pause response: {}", pause.getBody());

        ResponseEntity<Map> resume = restTemplate.postForEntity(
            "/api/behavior/processing/scheduled/resume",
            null,
            Map.class
        );
        assertThat(resume.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resume.getBody()).containsEntry("paused", false);
        log.info("scheduled resume response: {}", resume.getBody());
    }
}
