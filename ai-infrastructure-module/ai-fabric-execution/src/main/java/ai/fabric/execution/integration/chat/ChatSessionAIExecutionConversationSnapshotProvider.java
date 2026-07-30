package ai.fabric.execution.integration.chat;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.service.ConversationHistorySnapshot;
import ai.fabric.execution.gateway.AIExecutionConversationSnapshotProvider;
import ai.fabric.execution.gateway.ConversationBinding;
import ai.fabric.execution.specialist.SpecialistId;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Objects;

/**
 * Freezes the chat module's already authorized, bounded history projection.
 */
public final class ChatSessionAIExecutionConversationSnapshotProvider
    implements AIExecutionConversationSnapshotProvider {

    private final ChatSessionService chatSessionService;
    private final CanonicalJsonSupport canonicalJson;
    private final Clock clock;

    public ChatSessionAIExecutionConversationSnapshotProvider(
        ChatSessionService chatSessionService,
        CanonicalJsonSupport canonicalJson,
        Clock clock
    ) {
        this.chatSessionService = Objects.requireNonNull(
            chatSessionService,
            "chatSessionService is required"
        );
        this.canonicalJson = Objects.requireNonNull(
            canonicalJson,
            "canonicalJson is required"
        );
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    @Override
    public ApprovedConversationSnapshot capture(
        ConversationBinding binding,
        String interactionTurnId,
        SpecialistId dialogueOwner
    ) {
        Objects.requireNonNull(binding, "binding is required");
        Objects.requireNonNull(dialogueOwner, "dialogueOwner is required");
        if (binding.approvedSnapshotToken() != null) {
            throw new IllegalArgumentException(
                "Snapshot capture requires a plain conversation binding"
            );
        }

        ConversationHistorySnapshot history =
            chatSessionService.getConversationSnapshot(
                binding.conversationId(),
                binding.userId()
            );

        LinkedHashMap<String, Object> revisionInput =
            new LinkedHashMap<>();
        revisionInput.put("ownerId", binding.userId());
        revisionInput.put("conversationId", binding.conversationId());
        revisionInput.put(
            "sourceTurnCount",
            history.sourceTurnCount()
        );
        revisionInput.put("historyMessages", history.messages());

        return new ApprovedConversationSnapshot(
            interactionTurnId,
            binding.userId(),
            binding.conversationId(),
            dialogueOwner.toString(),
            canonicalJson.hashValue(revisionInput),
            history.sourceTurnCount(),
            history.messages(),
            clock.instant()
        );
    }
}
