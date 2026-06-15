package ai.fabric.integration;

import ai.fabric.config.TestConfiguration;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.NextStepRecommendation;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.entity.IntentHistory;
import ai.fabric.access.AIAccessControlService;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.repository.IntentHistoryRepository;
import ai.fabric.spi.RAGProvider;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.security.AISecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doReturn;

@SpringBootTest(classes = TestConfiguration.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
    "ai.intent-extraction.progressive.enabled=false",
    "ai.actions.builtin.vector-management.enabled=true"
})
class RAGIntegrationFlowTest {

    private static final String ACTION_QUERY = "Please clear my index and remove card 4111-1111-1111-1111.";
    private static final String INFO_QUERY = "What's your refund policy?";
    private static final String COMPOUND_QUERY = "Pause my subscription and show special offers.";
    private static final String OOS_QUERY = "Build me a spaceship!";

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private IntentHistoryRepository historyRepository;

    @Autowired
    private VectorDatabaseService vectorDatabaseService;

    @MockBean
    private IntentQueryExtractor intentQueryExtractor;

    @SpyBean
    private RAGProvider ragProvider;

    @MockBean
    private AISecurityService securityService;

    @MockBean
    private AIAccessControlService accessControlService;

    @MockBean
    private Clock clock;

    @MockBean
    private AICoreService aiCoreService;

    @BeforeEach
    void resetState() {
        // Use doReturn to avoid Mockito type inference issues on Clock
        doReturn(ZoneOffset.UTC).when(clock).getZone();
        doReturn(Instant.parse("2025-01-01T00:00:00Z")).when(clock).instant();
        vectorDatabaseService.clearVectors();
        historyRepository.deleteAll();
        when(securityService.analyzeRequest(any())).thenReturn(
            ai.fabric.dto.AISecurityResponse.builder()
                .shouldBlock(false)
                .accessAllowed(true)
                .success(true)
                .build()
        );
        when(accessControlService.checkAccess(any())).thenReturn(
            ai.fabric.dto.AIAccessControlResponse.builder()
                .accessGranted(true)
                .success(true)
                .build()
        );
    }

    @Test
    void shouldHandleActionFlowAndPersistHistory() {
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder()
                .intents(List.of(Intent.builder()
                    .type(IntentType.ACTION)
                    .action("clear_vector_index")
                    .confidence(0.95)
                    .nextStepRecommended(NextStepRecommendation.builder()
                        .intent("show_refund_timeline")
                        .query("What is the refund timeline?")
                        .confidence(0.8)
                        .build())
                    .build()))
                .metadata(Map.of("sessionId", "session-action"))
                .build());

        vectorDatabaseService.storeVector("doc", "123", "content", List.of(0.1, 0.2), Map.of());

        OrchestrationResult result = orchestrator.orchestrate(ACTION_QUERY, ai.fabric.intent.orchestration.OrchestrationContext.forUser("user-action"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSanitizedPayload()).isNotEmpty();
        assertThat(result.getSanitizedPayload().get("message")).isNotNull();

        assertRecordedHistory("user-action", 1);

        var history = historyRepository.findByUserIdOrderByCreatedAtDesc("user-action").getFirst();
        assertThat(history.getRedactedQuery()).doesNotContain("4111-1111-1111-1111");
        assertThat(history.getSensitiveDataTypes()).contains("CREDIT_CARD");
        assertThat(history.getIntentCount()).isEqualTo(1);
        assertThat(history.getExecutionStatus()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED.name());
    }

    @Test
    void shouldHandleInformationFlowAndPersistHistory() {
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder()
                .intents(List.of(Intent.builder()
                    .type(IntentType.INFORMATION)
                    .intent("show_refund_policy")
                    .confidence(0.9)
                    // Vector spaces are validated against configured entity types; use a valid test space.
                    .vectorSpace("document")
                    .requiresGeneration(true)
                    .build()))
                .build());

        doReturn(RAGResponse.builder()
            .context("Refunds are processed within 5 business days.")
            .documents(List.of())
            .success(true)
            .build()).when(ragProvider).performRAGQuery(any(RAGRequest.class));
        when(aiCoreService.generateTextResponse(anyString(), any(LlmPurpose.class))).thenReturn(
            AIGenerationResponse.builder()
                .content("Refunds are processed within 5 business days.")
                .build()
        );

        OrchestrationResult result = orchestrator.orchestrate(INFO_QUERY, ai.fabric.intent.orchestration.OrchestrationContext.forUser("user-info"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getSanitizedPayload()).isNotEmpty();

        assertRecordedHistory("user-info", 1);
        var history = historyRepository.findByUserIdOrderByCreatedAtDesc("user-info").getFirst();
        assertThat(history.getRedactedQuery()).isEqualTo(INFO_QUERY);
        assertThat(history.getIntentCount()).isEqualTo(1);
        assertThat(history.getResultJson()).contains("Refunds are processed");
    }

    @Test
    void shouldHandleCompoundFlowAndPersistHistory() {
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder()
                .intents(List.of(
                    Intent.builder()
                        .type(IntentType.ACTION)
                        .action("clear_vector_index")
                        .confidence(0.9)
                        .build(),
                    Intent.builder()
                        .type(IntentType.INFORMATION)
                        .intent("show_special_offers")
                        .confidence(0.85)
                        // Vector spaces are validated against configured entity types; use a valid test space.
                        .vectorSpace("document")
                        .build()))
                .build());

        doReturn(RAGResponse.builder().context("Here are current offers.").build())
            .when(ragProvider).performRag(any(RAGRequest.class));

        OrchestrationResult result = orchestrator.orchestrate(COMPOUND_QUERY, ai.fabric.intent.orchestration.OrchestrationContext.forUser("user-compound"));

        // Provider-agnostic contract: compound wrappers are normalized into a stable top-level outcome.
        // For action + information compounds, the primary outcome should be the action.
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);
        assertRecordedHistory("user-compound", 1);

        IntentHistory history = historyRepository.findByUserIdOrderByCreatedAtDesc("user-compound").getFirst();
        assertThat(history.getIntentCount()).isEqualTo(2);
        assertThat(history.getResultJson()).contains("ACTION_EXECUTED");
        assertThat(history.getRedactedQuery()).contains("Pause my subscription");
    }

    @Test
    void shouldHandleOutOfScopeAndPersistHistory() {
        when(intentQueryExtractor.extract(any(IntentExtractionInput.class), any(OrchestrationContext.class)))
            .thenReturn(MultiIntentResponse.builder()
                .intents(List.of(Intent.builder()
                    .type(IntentType.OUT_OF_SCOPE)
                    .intent("out_of_scope")
                    .confidence(0.6)
                    .build()))
                .build());

        OrchestrationResult result = orchestrator.orchestrate(OOS_QUERY, ai.fabric.intent.orchestration.OrchestrationContext.forUser("user-oos"));

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.OUT_OF_SCOPE);
        assertRecordedHistory("user-oos", 1);

        IntentHistory history = historyRepository.findByUserIdOrderByCreatedAtDesc("user-oos").getFirst();
        assertThat(history.getExecutionStatus()).isEqualTo(OrchestrationResultType.OUT_OF_SCOPE.name());
        assertThat(history.getRedactedQuery()).isEqualTo(OOS_QUERY);
    }

    private void assertRecordedHistory(String userId, int expectedCount) {
        List<IntentHistory> history = historyRepository.findByUserIdOrderByCreatedAtDesc(userId);
        assertThat(history).hasSize(expectedCount);
        assertThat(history.getFirst().getCreatedAt()).isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(history.getFirst().getExpiresAt()).isAfter(LocalDateTime.now());
    }
}
