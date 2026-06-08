package com.ai.infrastructure.intent.extraction;

import com.ai.infrastructure.dto.AIChatMessage;

import java.util.List;

/**
 * Typed input for intent extraction.
 *
 * <p>Separates the user's actual query (used for validation/post-processing and RAG) from the
 * current user message sent to the LLM (which may include bounded pinned-target context).</p>
 */
public record IntentExtractionInput(
    String userQuery,
    String currentUserMessage,
    List<AIChatMessage> historyMessages
) {

    public IntentExtractionInput {
        historyMessages = historyMessages != null ? List.copyOf(historyMessages) : List.of();
    }
}

