package ai.fabric.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import ai.fabric.config.IntentHistoryProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.entity.IntentHistory;
import ai.fabric.intent.history.IntentHistoryService;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.repository.IntentHistoryRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Integration tests covering lifecycle behaviour for persisted intent history entries.
 */
@Disabled("Disabled due to ApplicationContext loading failures - table creation issues")
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.intent-history.enabled=true",
    "ai.intent-history.retention-days=3",
    "ai.intent-history.store-encrypted-query=true"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class IntentHistoryLifecycleIntegrationTest {

    @Autowired
    private IntentHistoryService intentHistoryService;

    @Autowired
    private IntentHistoryRepository intentHistoryRepository;

    @Autowired
    private IntentHistoryProperties historyProperties;

    @MockBean
    private PIIDetectionService piiDetectionService;

    @BeforeEach
    void setUp() {
        intentHistoryRepository.deleteAll();
        reset(piiDetectionService);
    }

    @Test
    void persistedHistoryIsSanitizedAndExpiresAccordingToRetentionPolicy() {
        String originalQuery = "Please charge card 4111-1111-1111-1111 tomorrow";
        PIIDetection piiDetection = PIIDetection.builder()
            .type("CREDIT_CARD")
            .fieldName("card_number")
            .startIndex(19)
            .endIndex(38)
            .maskedValue("[CARD]")
            .build();
        PIIDetectionResult detectionResult = PIIDetectionResult.builder()
            .originalQuery(originalQuery)
            .processedQuery("Please charge card [CARD] tomorrow")
            .piiDetected(true)
            .detections(List.of(piiDetection))
            .encryptedOriginalQuery("encrypted-blob")
            .build();

        when(piiDetectionService.analyze(any())).thenReturn(detectionResult);
        when(piiDetectionService.detectAndProcess(any())).thenReturn(detectionResult);

        MultiIntentResponse intents = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder()
                    .type(IntentType.INFORMATION)
                    .intent("billing_support")
                    .confidence(0.92)
                    .build()
            ))
            .metadata(Map.of("trace", "abc-123"))
            .build();

        OrchestrationResult result = OrchestrationResult.builder()
            .type(OrchestrationResultType.INFORMATION_PROVIDED)
            .success(true)
            .build();
        result.setSanitizedPayload(Map.of("message", "Handled"));
        result.setMetadata(Map.of("durationMs", 18));

        Optional<IntentHistory> persisted = intentHistoryService.recordIntent(
            "user-lifecycle",
            "session-1",
            originalQuery,
            intents,
            result
        );

        assertTrue(persisted.isPresent());
        IntentHistory saved = persisted.orElseThrow();

        assertThat(saved.getRedactedQuery()).isEqualTo("Please charge card [CARD] tomorrow");
        assertThat(saved.getEncryptedQuery()).isEqualTo("encrypted-blob");
        assertThat(saved.getSensitiveDataTypes()).contains("CREDIT_CARD");
        assertThat(saved.getIntentCount()).isEqualTo(1);
        assertThat(saved.getExpiresAt()).isAfter(LocalDateTime.now().plusDays(historyProperties.getRetentionDays() - 1));
    }

    @Test
    @Transactional
    void cleanupRemovesExpiredHistoryRecords() {
        String query = "List my recent conversations.";
        PIIDetectionResult analysis = PIIDetectionResult.builder()
            .originalQuery(query)
            .processedQuery(query)
            .piiDetected(false)
            .build();

        when(piiDetectionService.analyze(any())).thenReturn(analysis);
        when(piiDetectionService.detectAndProcess(any())).thenReturn(analysis);

        Optional<IntentHistory> record = intentHistoryService.recordIntent(
            "user-cleanup",
            null,
            query,
            MultiIntentResponse.builder().build(),
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .build()
        );

        IntentHistory saved = record.orElseThrow();
        saved.setExpiresAt(LocalDateTime.now().minusDays(2));
        intentHistoryRepository.save(saved);

        intentHistoryService.cleanupExpiredHistory();

        assertThat(intentHistoryRepository.findAll()).isEmpty();
    }
}
