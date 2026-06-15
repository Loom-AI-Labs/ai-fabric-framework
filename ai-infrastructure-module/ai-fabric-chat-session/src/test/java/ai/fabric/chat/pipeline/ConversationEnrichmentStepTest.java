package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.dto.AIChatMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ConversationEnrichmentStepTest {

    @Test
    void shouldEnrichHistoryMessagesWhenHistoryPresent() {
        ChatSessionService service = mock(ChatSessionService.class);
        when(service.getConversationMessages(anyString(), anyString())).thenReturn(List.of(
            AIChatMessage.user("hi"),
            AIChatMessage.assistant("hello")
        ));
        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        when(pendingActionStore.peekPendingAction(anyString(), anyString())).thenReturn(Optional.empty());
        ActionDraftStore actionDraftStore = mock(ActionDraftStore.class);
        when(actionDraftStore.peekDraft(anyString(), anyString())).thenReturn(Optional.empty());

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setWindowSize(5);
        properties.setMaxContextChars(10_000);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties,
            pendingActionStore,
            actionDraftStore
        );

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("What next?", orchestrationContext);
        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getProcessedQuery()).isEqualTo("What next?");
        assertThat(updated.getHistoryMessages()).hasSize(2);
        assertThat(updated.getHistoryMessages().getFirst().getContent()).isEqualTo("hi");
        assertThat(updated.getMetadata()).containsKey("chat");
        @SuppressWarnings("unchecked")
        Map<String, Object> chatMeta = (Map<String, Object>) updated.getMetadata().get("chat");
        assertThat(chatMeta).containsEntry("conversationId", "conv-1");
    }

    @Test
    void shouldNotLoadHistoryForNeverPersistQuery() {
        ChatSessionService service = mock(ChatSessionService.class);
        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        ActionDraftStore actionDraftStore = mock(ActionDraftStore.class);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties,
            pendingActionStore,
            actionDraftStore
        );

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("correlation-1")
            .metadata(Map.of(OrchestrationContextMetadataKeys.QUERY_PERSISTENCE_MODE, "NEVER_PERSIST"))
            .build();

        PipelineContext context = PipelineContext.from("Explain this once", orchestrationContext);
        PipelineContext updated = step.process(context);

        assertThat(updated).isSameAs(context);
        assertThat(updated.getHistoryMessages()).isEmpty();
        assertThat(updated.getMetadata()).doesNotContainKey("chat");
        verifyNoInteractions(service, pendingActionStore, actionDraftStore);
    }

    @Test
    void shouldTerminateWhenAccessDenied() {
        ChatSessionService service = mock(ChatSessionService.class);
        when(service.getConversationMessages(anyString(), anyString())).thenThrow(new ChatSessionAccessDeniedException("denied"));
        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        when(pendingActionStore.peekPendingAction(anyString(), anyString())).thenReturn(Optional.empty());
        ActionDraftStore actionDraftStore = mock(ActionDraftStore.class);
        when(actionDraftStore.peekDraft(anyString(), anyString())).thenReturn(Optional.empty());

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties,
            pendingActionStore,
            actionDraftStore
        );

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("What next?", orchestrationContext);
        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult()).isNotNull();
        assertThat(updated.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.ERROR);
        assertThat(updated.getEarlyTerminationResult().getErrorCode()).isEqualTo("ACCESS_DENIED");
    }

    @Test
    void shouldSeedResolvedTargetsFromSessionMetadataWhenWithinReuseWindow() {
        ChatSessionService service = mock(ChatSessionService.class);
        when(service.getConversationMessages(anyString(), anyString())).thenReturn(List.of());

        ChatSession session = ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(java.util.List.of(
                ai.fabric.chat.domain.ChatTurn.builder().build(),
                ai.fabric.chat.domain.ChatTurn.builder().build(),
                ai.fabric.chat.domain.ChatTurn.builder().build()
            ))
            .sessionMetadata(Map.of(
                "lastResolvedTargetsTurnIndex", 2,
                "lastResolvedTargets", java.util.List.of(
                    Map.of(
                        "vectorSpace", "product",
                        "contentText", "snippet",
                        "contentTextTruncated", false,
                        "originSource", "REQUEST_ATTACHMENTS"
                    )
                )
            ))
            .createdAt(java.time.LocalDateTime.now())
            .lastInteractionAt(java.time.LocalDateTime.now())
            .build();

        when(service.getSession(anyString(), anyString())).thenReturn(session);

        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        when(pendingActionStore.peekPendingAction(anyString(), anyString())).thenReturn(Optional.empty());
        ActionDraftStore actionDraftStore = mock(ActionDraftStore.class);
        when(actionDraftStore.peekDraft(anyString(), anyString())).thenReturn(Optional.empty());

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setPinnedTargetReuseWindowTurns(3);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties,
            pendingActionStore,
            actionDraftStore
        );

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("summarize this", orchestrationContext);
        PipelineContext updated = step.process(context);

        assertThat(updated.getResolvedTargets()).hasSize(1);
        assertThat(updated.getResolvedTargets().getFirst().getId()).isNull();
        assertThat(updated.getResolvedTargets().getFirst().getContentText()).isEqualTo("snippet");
        assertThat(updated.getResolvedTargets().getFirst().getSource()).isEqualTo(ResolvedTargetSource.REQUEST_ATTACHMENTS);
        assertThat(updated.getPinnedTargetsContext()).startsWith("PINNED TARGETS (previously pinned; not current UI selection):");
    }

    // no vector database provider needed (pinned targets are persisted as full documents)
}
