package ai.fabric.chat.it;

import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.pipeline.ConversationRecordingStep;
import ai.fabric.chat.repository.ChatSessionRepository;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.privacy.pii.PIIDetectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = ChatSessionIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@org.springframework.context.annotation.Import(ConversationRecordingStepIntegrationTest.PiiTestConfig.class)
@ActiveProfiles("test")
class ConversationRecordingStepIntegrationTest {

    @Autowired
    private ChatSessionRepository chatSessionRepository;

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private ConversationRecordingStep recordingStep;

    @BeforeEach
    void setUp() {
        chatSessionRepository.deleteAll();
    }

    @Test
    void shouldRecordSanitizedAssistantMessageWhenAvailable() {
        String ownerId = "recording-user";
        String conversationId = "conv-" + UUID.randomUUID();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult intentResult = OrchestrationResult.builder()
            .type(OrchestrationResultType.OUT_OF_SCOPE)
            .success(true)
            .message("UNSANITIZED")
            .build();

        PipelineContext ctx = PipelineContext.from("Hello", orch).toBuilder()
            .intentResult(intentResult)
            .sanitizedPayload(Map.of("message", "SANITIZED"))
            .build();

        recordingStep.process(ctx);

        ChatSession session = chatSessionService.getSession(conversationId, ownerId);
        assertThat(session.getTurns()).hasSize(1);
        assertThat(session.getTurns().getFirst().getAiResponse()).isEqualTo("SANITIZED");
        assertThat(session.getTurns().getFirst().getUserQuery()).isEqualTo("Hello");
    }

    @Test
    void shouldSkipRecordingWhenPipelineTerminated() {
        String ownerId = "recording-user-terminated";
        String conversationId = "conv-" + UUID.randomUUID();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        PipelineContext ctx = PipelineContext.from("Hello", orch)
            .terminate(OrchestrationResult.error("Access denied"));

        recordingStep.process(ctx);

        assertThat(chatSessionRepository.findById(conversationId)).isEmpty();
    }

    @Test
    void shouldRedactUserQueryWhenPiiServiceDetectsSensitiveContent() {
        String ownerId = "recording-user-pii";
        String conversationId = "conv-" + UUID.randomUUID();

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId(ownerId)
            .conversationId(conversationId)
            .build();

        OrchestrationResult intentResult = OrchestrationResult.builder()
            .type(OrchestrationResultType.OUT_OF_SCOPE)
            .success(true)
            .message("OK")
            .build();

        PipelineContext ctx = PipelineContext.from("Email me at test@example.com", orch).toBuilder()
            .intentResult(intentResult)
            .sanitizedPayload(Map.of("message", "SANITIZED"))
            .build();

        recordingStep.process(ctx);

        ChatSession session = chatSessionService.getSession(conversationId, ownerId);
        assertThat(session.getTurns()).hasSize(1);
        assertThat(session.getTurns().getFirst().getUserQuery()).isEqualTo("Email me at [EMAIL]");
    }

    @TestConfiguration
    static class PiiTestConfig {
        @Bean
        @Primary
        PIIDetectionService testPiiDetectionService() {
            return new PIIDetectionService() {
                @Override
                public PIIDetectionResult detectAndProcess(String query) {
                    return analyze(query);
                }

                @Override
                public PIIDetectionResult analyze(String payload) {
                    if (payload == null) {
                        return PIIDetectionResult.builder().piiDetected(false).build();
                    }
                    if (!payload.contains("@")) {
                        return PIIDetectionResult.builder()
                            .originalQuery(payload)
                            .processedQuery(payload)
                            .piiDetected(false)
                            .build();
                    }
                    return PIIDetectionResult.builder()
                        .originalQuery(payload)
                        .processedQuery(payload.replace("test@example.com", "[EMAIL]"))
                        .piiDetected(true)
                        .build();
                }
            };
        }
    }
}
