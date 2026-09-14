package ai.fabric.execution.chain;

import ai.fabric.execution.manager.ConversationManagerContextValue;
import java.util.List;

/** Application-owned projection of a request into safe manager input. */
public interface SpecialistChainInputAdapter<I> {

    SpecialistChainComponentId id();

    Class<I> inputType();

    String currentUserMessage(I input);

    default List<ConversationManagerContextValue> applicationContext(
        I input
    ) {
        return List.of();
    }
}
