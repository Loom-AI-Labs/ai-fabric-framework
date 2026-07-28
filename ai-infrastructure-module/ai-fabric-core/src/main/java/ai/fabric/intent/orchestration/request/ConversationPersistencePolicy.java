package ai.fabric.intent.orchestration.request;

/**
 * Controls whether chat-session pipeline steps may read or persist conversation state.
 */
public enum ConversationPersistencePolicy {
    /**
     * Enrich the request from conversation history and record the completed pipeline turn.
     */
    CONVERSATION,

    /**
     * Enrich the request from conversation history without recording the pipeline result.
     *
     * <p>This is used by typed execution gateways that commit a turn only after their
     * application output contract has been validated.</p>
     */
    READ_ONLY,

    /**
     * Do not read or persist conversation state.
     */
    NEVER
}
