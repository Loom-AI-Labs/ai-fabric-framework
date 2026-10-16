package ai.fabric.chat.storage;

import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationScope;
import ai.fabric.chat.util.ActionDraftMetadata;
import ai.fabric.intent.actiondraft.ActionDraft;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.orchestration.conversation.ConversationStateScope;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;

/** Routes canonical drafts to conversation control and legacy calls to chat sessions. */
public final class ConversationAwareActionDraftStore implements ActionDraftStore {

    private final ActionDraftStore legacy;
    private final ObjectProvider<ConversationControlGateway> controlGateway;

    public ConversationAwareActionDraftStore(
        ActionDraftStore legacy,
        ObjectProvider<ConversationControlGateway> controlGateway
    ) {
        this.legacy = legacy;
        this.controlGateway = controlGateway;
    }

    @Override
    public Optional<ActionDraft> peekDraft(String conversationId, String ownerId) {
        return legacy.peekDraft(conversationId, ownerId);
    }

    @Override
    public void saveDraft(String conversationId, String ownerId, ActionDraft draft) {
        legacy.saveDraft(conversationId, ownerId, draft);
    }

    @Override
    public Optional<ActionDraft> popDraft(String conversationId, String ownerId) {
        return legacy.popDraft(conversationId, ownerId);
    }

    @Override
    public void clearDrafts(String conversationId, String ownerId) {
        legacy.clearDrafts(conversationId, ownerId);
    }

    @Override
    public Optional<ActionDraft> peekDraft(ConversationStateScope scope) {
        ConversationControlGateway gateway = requireControlGateway();
        return gateway.orchestrationState(toControlScope(scope), scope.expectedControlEpoch())
            .map(state -> ActionDraftMetadata.peek(state.metadata()))
            .filter(draft -> draft != null);
    }

    @Override
    public void saveDraft(ConversationStateScope scope, ActionDraft draft) {
        if (draft == null) {
            return;
        }
        ConversationControlGateway gateway = requireControlGateway();
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            ActionDraftMetadata.put(metadata, draft);
            return metadata;
        });
    }

    @Override
    public Optional<ActionDraft> popDraft(ConversationStateScope scope) {
        ConversationControlGateway gateway = requireControlGateway();
        AtomicReference<ActionDraft> popped = new AtomicReference<>();
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            popped.set(ActionDraftMetadata.peek(metadata));
            ActionDraftMetadata.clear(metadata);
            return metadata;
        });
        return Optional.ofNullable(popped.get());
    }

    @Override
    public void clearDrafts(ConversationStateScope scope) {
        popDraft(scope);
    }

    private ConversationScope toControlScope(ConversationStateScope scope) {
        return new ConversationScope(
            scope.tenantId(),
            scope.deploymentId(),
            scope.conversationId(),
            scope.ownerId()
        );
    }

    private ConversationControlGateway requireControlGateway() {
        ConversationControlGateway gateway = controlGateway != null
            ? controlGateway.getIfAvailable()
            : null;
        if (gateway == null) {
            throw new IllegalStateException(
                "ConversationControlGateway is required for trusted canonical conversation state"
            );
        }
        return gateway;
    }
}
