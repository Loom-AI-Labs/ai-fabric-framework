package ai.fabric.execution.gateway;

import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;

/**
 * Produces one backend-approved immutable conversation projection.
 */
@FunctionalInterface
public interface AIExecutionConversationSnapshotProvider {

    ApprovedConversationSnapshot capture(
        ConversationBinding binding,
        String interactionTurnId,
        SpecialistId dialogueOwner
    );
}
