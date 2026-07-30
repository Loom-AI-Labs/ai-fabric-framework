package ai.fabric.execution.manager;

public interface ConversationManagerGateway {

    <I> ConversationManagerTurnResult execute(
        ConversationManagerTurnRequest<I> request
    );
}
