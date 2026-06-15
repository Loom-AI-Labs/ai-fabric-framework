package ai.fabric.chat.strategy;

import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.dto.AIChatMessage;

import java.util.List;

public interface MemoryStrategy {

    List<ChatTurn> prune(List<ChatTurn> history, int limit);

    List<AIChatMessage> toMessages(List<ChatTurn> prunedHistory);

    String getStrategyName();
}
