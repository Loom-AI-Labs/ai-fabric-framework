package ai.fabric.chat.it;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.it.actions.SafeEchoActionHandler;
import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.IntentQueryExtractor;
import ai.fabric.intent.extraction.IntentExtractionInput;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.security.AISecurityService;
import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
        "ai.intent-extraction.progressive.enabled=false"
    }
)
@ActiveProfiles("test")
class ChatAnonymousActionDeniedIntegrationTest {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private Pipeline pipeline;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private AISecurityService securityService;

    @MockBean
    private IntentQueryExtractor intentQueryExtractor;

    @BeforeEach
    void setUp() {
        chatSessionRepository.deleteAll();
    }

    @Test
    void shouldDenyAnonymousActionAndRecordTurn() {
        MultiIntentResponse response = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder()
                .type(IntentType.ACTION)
                .intent(SafeEchoActionHandler.ACTION_NAME)
                .action(SafeEchoActionHandler.ACTION_NAME)
                .confidence(0.9)
                .actionParams(Map.of("message", "hello"))
                .build()))
            .orchestrationStrategy("ADMIT_UNKNOWN")
            .build();

        when(intentQueryExtractor.extract(
            any(IntentExtractionInput.class),
            any(ai.fabric.intent.orchestration.OrchestrationContext.class)
        )).thenReturn(response);

        String sessionId = "anon-session-" + UUID.randomUUID();
        String conversationId = "conv-" + UUID.randomUUID();

        AISecurityResponse security = securityService.analyzeRequest(AISecurityRequest.builder()
            .requestId("security-" + UUID.randomUUID())
            .authContext(AIAccessSubjectContext.builder()
                .sessionId(sessionId)
                .subjectType("ANONYMOUS")
                .build())
            .content("Please echo hello")
            .operationType("INTENT_QUERY")
            .build());

        assertThat(security.getShouldBlock())
            .withFailMessage("Security unexpectedly blocked anonymous request: threats=%s error=%s",
                security.getThreatsDetected(),
                security.getErrorMessage())
            .isFalse();

        OrchestrationContext orch = OrchestrationContext.builder()
            .sessionId(sessionId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult result = pipeline.execute("Please echo hello", orch);

        assertThat(result).isNotNull();
        assertThat(result.getType())
            .withFailMessage("Unexpected type=%s success=%s errorCode=%s message=%s dataKeys=%s",
                result.getType(),
                result.isSuccess(),
                result.getErrorCode(),
                result.getMessage(),
                result.getData() != null ? result.getData().keySet() : null)
            .isEqualTo(OrchestrationResultType.ACTION_DENIED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotBlank();
        assertThat(result.getSanitizedPayload()).isNotEmpty();

        ChatSession session = chatSessionService.getSession(conversationId, sessionId);
        assertThat(session.getTurns()).hasSize(1);
        assertThat(session.getTurns().getFirst().getAiResponse()).isEqualTo(result.getSanitizedPayload().get("message"));
    }
}
