package ai.fabric.execution.gateway;

import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;

/**
 * Issues and resolves short-lived opaque approvals for frozen snapshots.
 */
public interface AIExecutionConversationSnapshotRegistry {

    ConversationBinding approve(
        ConversationBinding binding,
        ApprovedConversationSnapshot snapshot
    );

    ApprovedConversationSnapshot consume(ConversationBinding binding);

    void release(ConversationBinding binding);
}
