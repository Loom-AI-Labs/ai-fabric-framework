package ai.fabric.chat.it;

import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "ai.intent-extraction.progressive.enabled=false"
    }
)
@ActiveProfiles("test")
class ChatFollowUpReferenceIntegrationTest {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private Pipeline pipeline;

    @MockBean
    private IntentQueryExtractor intentQueryExtractor;

    @BeforeEach
    void setUp() {
        chatSessionRepository.deleteAll();
    }

    @Test
    void shouldIncludeConversationHistoryInSecondTurnIntentExtraction() {
        MultiIntentResponse outOfScope = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.OUT_OF_SCOPE)
                .intent("out_of_scope")
                .confidence(0.9)
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        when(intentQueryExtractor.extract(
            any(IntentExtractionInput.class),
            any(ai.fabric.intent.orchestration.OrchestrationContext.class)
        )).thenReturn(outOfScope);

        String ownerId = "chat-followup-user";
        String conversationId = "conv-" + UUID.randomUUID();
        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        pipeline.execute("I want to echo hello.", orch);
        pipeline.execute("Do it.", orch);

        ArgumentCaptor<IntentExtractionInput> inputCaptor = ArgumentCaptor.forClass(IntentExtractionInput.class);
        verify(intentQueryExtractor, times(2)).extract(
            inputCaptor.capture(),
            any(ai.fabric.intent.orchestration.OrchestrationContext.class)
        );

        List<IntentExtractionInput> inputs = inputCaptor.getAllValues();
        assertThat(inputs).hasSize(2);

        IntentExtractionInput first = inputs.getFirst();
        assertThat(first.userQuery()).isEqualTo("I want to echo hello.");
        assertThat(first.historyMessages()).isEmpty();

        IntentExtractionInput second = inputs.get(1);
        assertThat(second.userQuery()).isEqualTo("Do it.");
        assertThat(second.historyMessages()).isNotEmpty();
        assertThat(second.historyMessages().stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("I want to echo hello."))).isTrue();
    }
}
