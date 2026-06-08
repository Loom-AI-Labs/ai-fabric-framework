package com.ai.infrastructure.chat.strategy;

import com.ai.infrastructure.chat.domain.ChatTurn;
import com.ai.infrastructure.dto.AIChatMessage;

import java.util.List;

public interface MemoryStrategy {

    List<ChatTurn> prune(List<ChatTurn> history, int limit);

    List<AIChatMessage> toMessages(List<ChatTurn> prunedHistory);

    String getStrategyName();
}
