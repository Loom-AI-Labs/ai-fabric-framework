package ai.fabric.chat.strategy;

import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.dto.AIChatMessage;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class SlidingWindowMemoryStrategy implements MemoryStrategy {

    @Override
    public List<ChatTurn> prune(List<ChatTurn> history, int limit) {
        if (history == null || history.isEmpty() || limit <= 0) {
            return List.of();
        }
        if (history.size() <= limit) {
            return Collections.unmodifiableList(new ArrayList<>(history));
        }
        int startIndex = Math.max(0, history.size() - limit);
        return Collections.unmodifiableList(new ArrayList<>(history.subList(startIndex, history.size())));
    }

    @Override
    public List<AIChatMessage> toMessages(List<ChatTurn> prunedHistory) {
        if (prunedHistory == null || prunedHistory.isEmpty()) {
            return List.of();
        }

        List<AIChatMessage> messages = new java.util.ArrayList<>();
        for (ChatTurn turn : prunedHistory) {
            if (turn == null) {
                continue;
            }

            if (StringUtils.hasText(turn.getUserQuery())) {
                messages.add(AIChatMessage.user(turn.getUserQuery()));
            }

            String assistant = turn.toAssistantMessageContent();
            if (StringUtils.hasText(assistant)) {
                messages.add(AIChatMessage.assistant(assistant));
            }
        }

        return Collections.unmodifiableList(messages);
    }

    @Override
    public String getStrategyName() {
        return "SLIDING_WINDOW";
    }
}
