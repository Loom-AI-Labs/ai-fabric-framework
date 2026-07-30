package ai.fabric.chat.service;

import ai.fabric.dto.AIChatMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * Authorized, bounded conversation history captured in one service transaction.
 */
public record ConversationHistorySnapshot(
    long sourceTurnCount,
    List<AIChatMessage> messages
) {

    public ConversationHistorySnapshot {
        if (sourceTurnCount < 0) {
            throw new IllegalArgumentException(
                "sourceTurnCount cannot be negative"
            );
        }
        messages = copyMessages(messages);
    }

    @Override
    public List<AIChatMessage> messages() {
        return copyMessages(messages);
    }

    private static List<AIChatMessage> copyMessages(
        List<AIChatMessage> source
    ) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        ArrayList<AIChatMessage> copy = new ArrayList<>(source.size());
        for (AIChatMessage message : source) {
            if (message == null) {
                throw new IllegalArgumentException(
                    "messages cannot contain null entries"
                );
            }
            copy.add(new AIChatMessage(
                message.getRole(),
                message.getContent()
            ));
        }
        return List.copyOf(copy);
    }
}
