package ai.fabric.execution.integration.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.service.ConversationHistorySnapshot;
import ai.fabric.dto.AIChatMessage;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatSessionAIExecutionConversationSnapshotProviderTest {

    @Test
    void capturesTheAuthorizedBoundedHistoryAndStableRevision() {
        ChatSessionService sessions = mock(ChatSessionService.class);
        when(sessions.getConversationSnapshot(
            "conversation-9",
            "user-7"
        )).thenReturn(new ConversationHistorySnapshot(
            12,
            List.of(
                AIChatMessage.user("Why am I blocked?"),
                AIChatMessage.assistant("Payment is missing.")
            )
        ));
        ChatSessionAIExecutionConversationSnapshotProvider provider =
            provider(sessions);

        var first = provider.capture(
            new ConversationBinding("user-7", "conversation-9"),
            "turn-1",
            SpecialistId.of("account-resolver", "1")
        );
        var second = provider.capture(
            new ConversationBinding("user-7", "conversation-9"),
            "turn-2",
            SpecialistId.of("account-resolver", "1")
        );

        assertThat(first.ownerId()).isEqualTo("user-7");
        assertThat(first.conversationId()).isEqualTo("conversation-9");
        assertThat(first.dialogueOwnerSpecialist())
            .isEqualTo("account-resolver@1");
        assertThat(first.sourceTurnCount()).isEqualTo(12);
        assertThat(first.historyMessages())
            .extracting(AIChatMessage::getContent)
            .containsExactly(
                "Why am I blocked?",
                "Payment is missing."
            );
        assertThat(first.revision()).isEqualTo(second.revision());
        verify(sessions, times(2)).getConversationSnapshot(
            "conversation-9",
            "user-7"
        );
    }

    @Test
    void refusesCallerSuppliedSnapshotApproval() {
        ChatSessionService sessions = mock(ChatSessionService.class);

        assertThatThrownBy(() -> provider(sessions).capture(
            new ConversationBinding(
                "user-7",
                "conversation-9",
                "caller-token"
            ),
            "turn-1",
            SpecialistId.of("account-resolver", "1")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("plain conversation binding");
    }

    @Test
    void changesRevisionWhenTheApprovedProjectionChanges() {
        ChatSessionService sessions = mock(ChatSessionService.class);
        when(sessions.getConversationSnapshot(
            "conversation-9",
            "user-7"
        )).thenReturn(
            new ConversationHistorySnapshot(
                1,
                List.of(AIChatMessage.user("First question"))
            ),
            new ConversationHistorySnapshot(
                2,
                List.of(
                    AIChatMessage.user("First question"),
                    AIChatMessage.assistant("First answer")
                )
            )
        );
        ChatSessionAIExecutionConversationSnapshotProvider provider =
            provider(sessions);
        ConversationBinding binding =
            new ConversationBinding("user-7", "conversation-9");
        SpecialistId owner =
            SpecialistId.of("account-resolver", "1");

        String first = provider.capture(
            binding,
            "turn-1",
            owner
        ).revision();
        String second = provider.capture(
            binding,
            "turn-2",
            owner
        ).revision();

        assertThat(first).isNotEqualTo(second);
    }

    private ChatSessionAIExecutionConversationSnapshotProvider provider(
        ChatSessionService sessions
    ) {
        return new ChatSessionAIExecutionConversationSnapshotProvider(
            sessions,
            new CanonicalJsonSupport(
                new ObjectMapper().findAndRegisterModules()
            ),
            Clock.fixed(
                Instant.parse("2026-07-29T12:00:00Z"),
                ZoneOffset.UTC
            )
        );
    }
}
