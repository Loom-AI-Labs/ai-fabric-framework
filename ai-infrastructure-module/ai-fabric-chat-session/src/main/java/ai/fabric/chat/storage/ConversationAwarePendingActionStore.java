package ai.fabric.chat.storage;

import ai.fabric.chat.control.ConversationControlGateway;
import ai.fabric.chat.control.ConversationScope;
import ai.fabric.chat.util.ConfirmationStack;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.conversation.ConversationStateScope;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.ObjectProvider;

/** Routes trusted canonical state to conversation control and legacy calls to chat sessions. */
public final class ConversationAwarePendingActionStore implements PendingActionStore {

    private final PendingActionStore legacy;
    private final ObjectProvider<ConversationControlGateway> controlGateway;
    private final int maxStackDepth;

    public ConversationAwarePendingActionStore(
        PendingActionStore legacy,
        ObjectProvider<ConversationControlGateway> controlGateway,
        int maxStackDepth
    ) {
        this.legacy = legacy;
        this.controlGateway = controlGateway;
        this.maxStackDepth = Math.max(1, maxStackDepth);
    }

    @Override
    public Optional<PendingAction> getPendingAction(String conversationId, String ownerId) {
        return legacy.getPendingAction(conversationId, ownerId);
    }

    @Override
    public void savePendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        legacy.savePendingAction(conversationId, ownerId, pendingAction);
    }

    @Override
    public void clearPendingAction(String conversationId, String ownerId) {
        legacy.clearPendingAction(conversationId, ownerId);
    }

    @Override
    public Optional<PendingAction> peekPendingAction(String conversationId, String ownerId) {
        return legacy.peekPendingAction(conversationId, ownerId);
    }

    @Override
    public void pushPendingAction(String conversationId, String ownerId, PendingAction pendingAction) {
        legacy.pushPendingAction(conversationId, ownerId, pendingAction);
    }

    @Override
    public Optional<PendingAction> popPendingAction(String conversationId, String ownerId) {
        return legacy.popPendingAction(conversationId, ownerId);
    }

    @Override
    public Optional<PendingAction> popPendingAction(
        String conversationId,
        String ownerId,
        PendingAction expected
    ) {
        return legacy.popPendingAction(conversationId, ownerId, expected);
    }

    @Override
    public void clearPendingActions(String conversationId, String ownerId) {
        legacy.clearPendingActions(conversationId, ownerId);
    }

    @Override
    public List<PendingAction> getPendingActionStack(String conversationId, String ownerId) {
        return legacy.getPendingActionStack(conversationId, ownerId);
    }

    @Override
    public void replacePendingActionStack(
        String conversationId,
        String ownerId,
        List<PendingAction> stackTopFirst
    ) {
        legacy.replacePendingActionStack(conversationId, ownerId, stackTopFirst);
    }

    @Override
    public boolean replacePendingActionStack(
        String conversationId,
        String ownerId,
        List<PendingAction> expectedTopFirst,
        List<PendingAction> replacementTopFirst
    ) {
        return legacy.replacePendingActionStack(
            conversationId,
            ownerId,
            expectedTopFirst,
            replacementTopFirst
        );
    }

    @Override
    public Optional<PendingAction> getPendingAction(ConversationStateScope scope) {
        return peekPendingAction(scope);
    }

    @Override
    public void savePendingAction(ConversationStateScope scope, PendingAction pendingAction) {
        replacePendingActionStack(scope, pendingAction == null ? List.of() : List.of(pendingAction));
    }

    @Override
    public void clearPendingAction(ConversationStateScope scope) {
        clearPendingActions(scope);
    }

    @Override
    public Optional<PendingAction> peekPendingAction(ConversationStateScope scope) {
        ConversationControlGateway gateway = requireControlGateway();
        return gateway.orchestrationState(toControlScope(scope), scope.expectedControlEpoch())
            .map(state -> ConfirmationStack.peek(state.metadata()))
            .filter(action -> action != null);
    }

    @Override
    public void pushPendingAction(ConversationStateScope scope, PendingAction pendingAction) {
        if (pendingAction == null) {
            return;
        }
        ConversationControlGateway gateway = requireControlGateway();
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            ConfirmationStack.push(metadata, pendingAction, maxStackDepth);
            return metadata;
        });
    }

    @Override
    public Optional<PendingAction> popPendingAction(ConversationStateScope scope) {
        ConversationControlGateway gateway = requireControlGateway();
        AtomicReference<PendingAction> popped = new AtomicReference<>();
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            popped.set(ConfirmationStack.pop(metadata));
            return metadata;
        });
        return Optional.ofNullable(popped.get());
    }

    @Override
    public Optional<PendingAction> popPendingAction(
        ConversationStateScope scope,
        PendingAction expected
    ) {
        if (expected == null) {
            return Optional.empty();
        }
        ConversationControlGateway gateway = requireControlGateway();
        AtomicReference<PendingAction> popped = new AtomicReference<>();
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            PendingAction current = ConfirmationStack.peek(metadata);
            if (expected.equals(current)) {
                popped.set(ConfirmationStack.pop(metadata));
            }
            return metadata;
        });
        return Optional.ofNullable(popped.get());
    }

    @Override
    public void clearPendingActions(ConversationStateScope scope) {
        ConversationControlGateway gateway = requireControlGateway();
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            ConfirmationStack.clear(metadata);
            return metadata;
        });
    }

    @Override
    public List<PendingAction> getPendingActionStack(ConversationStateScope scope) {
        ConversationControlGateway gateway = requireControlGateway();
        return gateway.orchestrationState(toControlScope(scope), scope.expectedControlEpoch())
            .map(state -> ConfirmationStack.getAll(state.metadata()))
            .orElse(List.of());
    }

    @Override
    public void replacePendingActionStack(ConversationStateScope scope, List<PendingAction> stackTopFirst) {
        ConversationControlGateway gateway = requireControlGateway();
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            ConfirmationStack.replace(metadata, stackTopFirst, maxStackDepth);
            return metadata;
        });
    }

    @Override
    public boolean replacePendingActionStack(
        ConversationStateScope scope,
        List<PendingAction> expectedTopFirst,
        List<PendingAction> replacementTopFirst
    ) {
        ConversationControlGateway gateway = requireControlGateway();
        java.util.concurrent.atomic.AtomicBoolean replaced = new java.util.concurrent.atomic.AtomicBoolean();
        List<PendingAction> expected = expectedTopFirst == null ? List.of() : List.copyOf(expectedTopFirst);
        gateway.updateOrchestrationState(toControlScope(scope), scope.expectedControlEpoch(), metadata -> {
            if (expected.equals(ConfirmationStack.getAll(metadata))) {
                ConfirmationStack.replace(metadata, replacementTopFirst, maxStackDepth);
                replaced.set(true);
            }
            return metadata;
        });
        return replaced.get();
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
