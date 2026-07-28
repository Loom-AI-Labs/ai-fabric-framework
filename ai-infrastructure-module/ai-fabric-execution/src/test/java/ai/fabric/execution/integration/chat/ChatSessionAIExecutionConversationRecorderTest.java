package ai.fabric.execution.integration.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.PIIDetection;
import ai.fabric.dto.PIIDetectionResult;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.privacy.pii.PIIDetectionService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class ChatSessionAIExecutionConversationRecorderTest {

    @Test
    void recordsValidatedTurnForBoundOwnerAndConversation() {
        ChatSessionService sessions = mock(ChatSessionService.class);
        ChatSessionAIExecutionConversationRecorder recorder =
            new ChatSessionAIExecutionConversationRecorder(
                sessions,
                provider(null)
            );

        recorder.record(
            new ConversationBinding("user-7", "conversation-9"),
            "Why is my account blocked?",
            "{\"assessment\":\"BLOCKED\"}",
            Map.of("_validated", true)
        );

        verify(sessions).recordTurn(
            "conversation-9",
            "user-7",
            "Why is my account blocked?",
            "{\"assessment\":\"BLOCKED\"}",
            Map.of("_validated", true)
        );
    }

    @Test
    void redactsDetectedPiiBeforePersistence() {
        ChatSessionService sessions = mock(ChatSessionService.class);
        PIIDetectionService pii = mock(PIIDetectionService.class);
        String input = "Email me at user@example.com";
        when(pii.analyze(input)).thenReturn(
            PIIDetectionResult.builder()
                .piiDetected(true)
                .processedQuery("Email me at [EMAIL]")
                .build()
        );
        when(pii.analyze("{\"assessment\":\"READY\"}")).thenReturn(
            PIIDetectionResult.builder().piiDetected(false).build()
        );
        ChatSessionAIExecutionConversationRecorder recorder =
            new ChatSessionAIExecutionConversationRecorder(
                sessions,
                provider(pii)
            );

        recorder.record(
            new ConversationBinding("user-7", "conversation-9"),
            input,
            "{\"assessment\":\"READY\"}",
            Map.of()
        );

        verify(sessions).recordTurn(
            "conversation-9",
            "user-7",
            "Email me at [EMAIL]",
            "{\"assessment\":\"READY\"}",
            Map.of()
        );
    }

    @Test
    void failsClosedWhenPiiDetectionCannotProduceSafeText() {
        ChatSessionService sessions = mock(ChatSessionService.class);
        PIIDetectionService pii = mock(PIIDetectionService.class);
        when(pii.analyze("card 4242")).thenReturn(
            PIIDetectionResult.builder()
                .piiDetected(true)
                .detections(List.of(
                    PIIDetection.builder()
                        .startIndex(5)
                        .endIndex(9)
                        .maskedValue("")
                        .build()
                ))
                .build()
        );
        ChatSessionAIExecutionConversationRecorder recorder =
            new ChatSessionAIExecutionConversationRecorder(
                sessions,
                provider(pii)
            );

        assertThatThrownBy(() -> recorder.record(
            new ConversationBinding("user-7", "conversation-9"),
            "card 4242",
            "{\"assessment\":\"READY\"}",
            Map.of()
        ))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("could not be redacted");
        verify(sessions, never()).recordTurn(
            eq("conversation-9"),
            eq("user-7"),
            eq("card 4242"),
            eq("{\"assessment\":\"READY\"}"),
            eq(Map.of())
        );
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PIIDetectionService> provider(
        PIIDetectionService service
    ) {
        ObjectProvider<PIIDetectionService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(service);
        return provider;
    }
}
