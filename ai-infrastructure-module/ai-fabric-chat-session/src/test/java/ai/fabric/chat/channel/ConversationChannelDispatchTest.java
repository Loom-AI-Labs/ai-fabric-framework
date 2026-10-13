package ai.fabric.chat.channel;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.chat.control.ConversationParticipantRole;
import ai.fabric.chat.control.ConversationScope;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConversationChannelDispatchTest {

    @Test
    void transcriptAndAttachmentCollectionsAreDefensivelyCopied() {
        Instant now = Instant.parse("2026-10-13T10:00:00Z");
        List<String> attachmentRefs = new ArrayList<>(List.of("attachment-1"));
        List<ConversationChannelMessage> transcript = new ArrayList<>(List.of(
            new ConversationChannelMessage(
                "message-1",
                ConversationParticipantRole.USER,
                "owner-1",
                null,
                "Please connect me to a person.",
                attachmentRefs,
                now
            )
        ));

        ConversationChannelDispatch dispatch = new ConversationChannelDispatch(
            "dispatch-1",
            ConversationChannelDispatchType.OPEN_HANDOVER,
            new ConversationScope("tenant-1", "deployment-1", "conversation-1", "owner-1"),
            "handover-1",
            "channel-1",
            2,
            null,
            null,
            List.of(),
            transcript,
            Map.of(),
            now
        );

        attachmentRefs.add("attachment-2");
        transcript.clear();

        assertThat(dispatch.transcript()).hasSize(1);
        assertThat(dispatch.transcript().getFirst().attachmentRefs())
            .containsExactly("attachment-1");
    }
}
