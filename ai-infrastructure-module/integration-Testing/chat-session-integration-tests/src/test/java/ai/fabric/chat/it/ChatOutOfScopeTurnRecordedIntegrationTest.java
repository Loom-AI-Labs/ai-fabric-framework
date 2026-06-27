package ai.fabric.chat.it;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@ActiveProfiles("test")
class ChatOutOfScopeTurnRecordedIntegrationTest {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private Pipeline pipeline;

    @Autowired
    private ChatSessionService chatSessionService;

    @MockitoBean
    private IntentQueryExtractor intentQueryExtractor;

    @BeforeEach
    void setUp() {
        chatSessionRepository.deleteAll();
    }

    @Test
    void shouldRecordOutOfScopeTurnEndToEndThroughPipeline() {
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.OUT_OF_SCOPE)
                .intent("out_of_scope")
                .confidence(0.9)
                .actionParams(Map.of("reason", "smalltalk"))
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        when(intentQueryExtractor.extract(
            any(IntentExtractionInput.class),
            any(ai.fabric.intent.orchestration.OrchestrationContext.class)
        )).thenReturn(response);

        String ownerId = "chat-out-of-scope-user";
        String conversationId = "conv-" + UUID.randomUUID();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult result = pipeline.execute("How are you today?", orch);

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.OUT_OF_SCOPE);
        assertThat(result.getMessage()).isNotBlank();
        assertThat(result.getSanitizedPayload()).isNotEmpty();
        assertThat(result.getSanitizedPayload().get("message")).isInstanceOf(String.class);

        ChatSession session = chatSessionService.getSession(conversationId, ownerId);
        assertThat(session.getTurns()).hasSize(1);

        String recordedResponse = session.getTurns().getFirst().getAiResponse();
        assertThat(recordedResponse).isEqualTo(result.getSanitizedPayload().get("message"));
    }
}
