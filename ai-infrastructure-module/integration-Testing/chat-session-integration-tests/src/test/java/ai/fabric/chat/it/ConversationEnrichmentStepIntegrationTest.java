package ai.fabric.chat.it;

import ai.fabric.chat.pipeline.ConversationEnrichmentStep;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "ai.chat.enabled=true",
        "ai.chat.window-size=2",
        "ai.chat.max-context-chars=2000"
    }
)
@ActiveProfiles("test")
class ConversationEnrichmentStepIntegrationTest {

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ConversationEnrichmentStep enrichmentStep;

    @Autowired
    private ai.fabric.chat.repository.ChatSessionRepository chatSessionRepository;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        chatSessionRepository.deleteAll();
    }

    @Test
    void shouldIncludeOnlyLastWindowTurnsInHistoryMessages() {
        String ownerId = "enrichment-user";
        String conversationId = "conv-" + UUID.randomUUID();

        chatSessionService.recordTurn(conversationId, ownerId, "Q1", "A1", Map.of());
        chatSessionService.recordTurn(conversationId, ownerId, "Q2", "A2", Map.of());
        chatSessionService.recordTurn(conversationId, ownerId, "Q3", "A3", Map.of());

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        PipelineContext ctx = PipelineContext.from("Current question", orch);
        PipelineContext enriched = enrichmentStep.process(ctx);

        assertThat(enriched.getProcessedQuery()).isEqualTo("Current question");
        assertThat(enriched.getHistoryMessages()).hasSize(4);
        assertThat(enriched.getHistoryMessages().get(0).getContent()).isEqualTo("Q2");
        assertThat(enriched.getHistoryMessages().get(1).getContent()).isEqualTo("A2");
        assertThat(enriched.getHistoryMessages().get(2).getContent()).isEqualTo("Q3");
        assertThat(enriched.getHistoryMessages().get(3).getContent()).isEqualTo("A3");
    }

    @Test
    void shouldBoundHistoryMessagesToMaxContextChars() {
        String ownerId = "enrichment-user-truncate";
        String conversationId = "conv-" + UUID.randomUUID();

        chatSessionService.recordTurn(conversationId, ownerId, "Q1-" + "x".repeat(900), "A1-" + "y".repeat(900), Map.of());
        chatSessionService.recordTurn(conversationId, ownerId, "Q2-" + "x".repeat(900), "A2-" + "y".repeat(900), Map.of());

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        PipelineContext ctx = PipelineContext.from("TAIL_SENTINEL", orch);
        PipelineContext enriched = enrichmentStep.process(ctx);

        int totalHistoryChars = enriched.getHistoryMessages().stream()
            .map(m -> m.getContent() != null ? m.getContent().length() : 0)
            .reduce(0, Integer::sum);
        assertThat(totalHistoryChars).isLessThanOrEqualTo(2000);

        // Bounded by dropping oldest whole messages (no substring truncation).
        assertThat(enriched.getHistoryMessages()).hasSize(2);
        assertThat(enriched.getHistoryMessages().get(0).getContent()).startsWith("Q2-");
        assertThat(enriched.getHistoryMessages().get(1).getContent()).startsWith("A2-");
    }

    @Test
    void shouldNoOpWhenConversationIdMissing() {
        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("enrichment-user-2")
            .build();

        PipelineContext ctx = PipelineContext.from("Hello", orch);
        PipelineContext enriched = enrichmentStep.process(ctx);

        assertThat(enriched.getProcessedQuery()).isEqualTo("Hello");
        assertThat(enriched.getHistoryMessages()).isEmpty();
    }
}
