package ai.fabric.intent.orchestration.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.fabric.dto.AIChatMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ApprovedConversationSnapshotTest {

    private static final String REVISION = "a".repeat(64);

    @Test
    void deeplyFreezesApprovedMessages() {
        AIChatMessage original = AIChatMessage.user("first question");
        List<AIChatMessage> source = new ArrayList<>(List.of(original));

        ApprovedConversationSnapshot snapshot = snapshot(source);
        original.setContent("changed by caller");
        source.clear();
        snapshot.historyMessages().getFirst().setContent("changed by reader");

        assertThat(snapshot.historyMessages())
            .extracting(AIChatMessage::getContent)
            .containsExactly("first question");
    }

    @Test
    void rejectsSystemMessagesAndInvalidRevision() {
        assertThatThrownBy(() -> snapshot(List.of(
            AIChatMessage.system("untrusted system text")
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("user or assistant");

        assertThatThrownBy(() -> new ApprovedConversationSnapshot(
            "turn-1",
            "owner-1",
            "conversation-1",
            "resolver@1",
            "not-a-revision",
            0,
            List.of(),
            Instant.parse("2026-07-29T12:00:00Z")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("SHA-256");
    }

    private ApprovedConversationSnapshot snapshot(
        List<AIChatMessage> messages
    ) {
        return new ApprovedConversationSnapshot(
            "turn-1",
            "owner-1",
            "conversation-1",
            "resolver@1",
            REVISION,
            1,
            messages,
            Instant.parse("2026-07-29T12:00:00Z")
        );
    }
}
