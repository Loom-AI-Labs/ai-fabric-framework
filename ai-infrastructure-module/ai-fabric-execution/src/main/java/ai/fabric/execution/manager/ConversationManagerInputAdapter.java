package ai.fabric.execution.manager;

import java.util.List;

/**
 * Application-owned projection from typed public input to safe manager input.
 */
public interface ConversationManagerInputAdapter<I> {

    ConversationManagerComponentId id();

    Class<I> inputType();

    String currentUserMessage(I input);

    List<ConversationManagerContextValue> applicationContext(I input);
}
