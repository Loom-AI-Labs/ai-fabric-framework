package ai.fabric.execution.gateway;

import java.util.Map;

/**
 * Commits a specialist conversation turn after typed output validation succeeds.
 */
@FunctionalInterface
public interface AIExecutionConversationRecorder {

    void record(
        ConversationBinding binding,
        String userInput,
        String assistantOutput,
        Map<String, Object> metadata
    );
}
