package ai.fabric.chat.it;

import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.vectorspace.RoutingResult;
import ai.fabric.intent.vectorspace.RoutingStrategy;
import ai.fabric.intent.vectorspace.VectorSpaceRouter;
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
class ChatClarificationThenExecuteIntegrationTest {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private Pipeline pipeline;

    @MockBean
    private IntentQueryExtractor intentQueryExtractor;

    @MockBean
    private VectorSpaceRouter vectorSpaceRouter;

    @BeforeEach
    void setUp() {
        chatSessionRepository.deleteAll();
    }

    @Test
    void shouldRecordClarificationAndIncludeItInNextTurnHistory() {
        when(vectorSpaceRouter.route(any(Intent.class), anyString())).thenReturn(RoutingResult.builder()
            .success(false)
            .strategy(RoutingStrategy.CLARIFICATION)
            .candidateSpaces(List.of("ragconversation"))
            .confidence(0.0d)
            .rationale("test-clarification")
            .build());

        MultiIntentResponse needsDomain = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("search")
                .confidence(0.9)
                .requiresRetrieval(true)
                .requiresGeneration(false)
                .vectorSpace(null)
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        MultiIntentResponse resolved = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.INFORMATION)
                .intent("search")
                .confidence(0.9)
                .requiresRetrieval(false)
                .requiresGeneration(true)
                .vectorSpace("ragconversation")
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        when(intentQueryExtractor.extract(
            any(IntentExtractionInput.class),
            any(ai.fabric.intent.orchestration.OrchestrationContext.class)
        )).thenReturn(needsDomain, resolved);

        String ownerId = "chat-clarify-then-exec-user";
        String conversationId = "conv-" + UUID.randomUUID();
        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult first = pipeline.execute("Search for policy details.", orch);
        assertThat(first.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);

        OrchestrationResult second = pipeline.execute("Use the ragconversation domain.", orch);
        assertThat(second.getType()).isNotEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(second.getType()).isNotEqualTo(OrchestrationResultType.ERROR);

        ArgumentCaptor<IntentExtractionInput> inputCaptor = ArgumentCaptor.forClass(IntentExtractionInput.class);
        verify(intentQueryExtractor, times(2)).extract(
            inputCaptor.capture(),
            any(ai.fabric.intent.orchestration.OrchestrationContext.class)
        );

        IntentExtractionInput secondInput = inputCaptor.getAllValues().get(1);
        assertThat(secondInput.userQuery()).isEqualTo("Use the ragconversation domain.");
        assertThat(secondInput.historyMessages().stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("Search for policy details."))).isTrue();
        assertThat(secondInput.historyMessages().stream().anyMatch(m -> m.getContent() != null && m.getContent().contains("Which knowledge base domain should I search?"))).isTrue();
    }
}
