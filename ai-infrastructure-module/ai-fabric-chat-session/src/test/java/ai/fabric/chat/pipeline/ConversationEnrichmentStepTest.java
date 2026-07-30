package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.execution.context.ExecutionPrincipal;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.chat.exception.ChatSessionAccessDeniedException;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.conversation.ApprovedConversationSnapshot;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.dto.AIChatMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setWindowSize(5);
        properties.setMaxContextChars(10_000);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties
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
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties
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
        verifyNoInteractions(service);
    }

    @Test
    void shouldPreferTypedNeverPersistencePolicy() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties
        );
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conversation-1")
            .build();
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Explain this once",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.NEVER
        ));

        PipelineContext updated = step.process(context);

        assertThat(updated).isSameAs(context);
        assertThat(updated.getHistoryMessages()).isEmpty();
        verifyNoInteractions(service);
    }

    @Test
    void shouldLoadHistoryForReadOnlyConversation() {
        ChatSessionService service = mock(ChatSessionService.class);
        when(service.getConversationMessages("conversation-1", "user-1"))
            .thenReturn(List.of(
                AIChatMessage.user("Why am I blocked?"),
                AIChatMessage.assistant("{\"assessment\":\"BLOCKED\"}")
            ));
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties
        );
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conversation-1")
            .build();
        PipelineContext context = PipelineContext.from(new OrchestrationRequest(
            "Specialist envelope",
            orchestrationContext,
            null,
            ConversationPersistencePolicy.READ_ONLY
        ));

        PipelineContext updated = step.process(context);

        assertThat(updated.getHistoryMessages())
            .extracting(AIChatMessage::getContent)
            .containsExactly(
                "Why am I blocked?",
                "{\"assessment\":\"BLOCKED\"}"
            );
    }

    @Test
    void shouldUseOnlyTheApprovedFrozenSnapshotForInteractiveExecution() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setPinnedTargetReuseWindowTurns(3);
        ApprovedConversationSnapshot snapshot =
            new ApprovedConversationSnapshot(
                "turn-1",
                "user-1",
                "conversation-1",
                "account-resolver@1",
                "a".repeat(64),
                4,
                List.of(
                    AIChatMessage.user("Why am I blocked?"),
                    AIChatMessage.assistant("A payment method is missing.")
                ),
                Instant.parse("2026-07-29T12:00:00Z")
            );
        PipelineContext context = PipelineContext.from(
            new OrchestrationRequest(
                "Add my card",
                OrchestrationContext.builder()
                    .userId("user-1")
                    .conversationId("conversation-1")
                    .approvedConversationSnapshot(snapshot)
                    .build(),
                null,
                ConversationPersistencePolicy.READ_ONLY
            )
        );

        PipelineContext updated =
            new ConversationEnrichmentStep(service, properties)
                .process(context);

        assertThat(updated.getHistoryMessages())
            .extracting(AIChatMessage::getContent)
            .containsExactly(
                "Why am I blocked?",
                "A payment method is missing."
            );
        assertThat(updated.getMetadata().get("chat"))
            .isInstanceOfSatisfying(Map.class, metadata ->
                assertThat(metadata)
                    .containsEntry("memoryStrategy", "APPROVED_SNAPSHOT")
                    .containsEntry("snapshotRevision", "a".repeat(64))
                    .containsEntry("sourceTurnCount", 4L)
            );
        assertThat(updated.getResolvedTargets()).isEmpty();
        verifyNoInteractions(service);
    }

    @Test
    void shouldDenyAnApprovedSnapshotBoundToAnotherOwner() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        ApprovedConversationSnapshot snapshot =
            new ApprovedConversationSnapshot(
                "turn-1",
                "other-user",
                "conversation-1",
                "account-resolver@1",
                "a".repeat(64),
                0,
                List.of(),
                Instant.parse("2026-07-29T12:00:00Z")
            );
        PipelineContext context = PipelineContext.from(
            new OrchestrationRequest(
                "Inspect",
                OrchestrationContext.builder()
                    .userId("user-1")
                    .conversationId("conversation-1")
                    .approvedConversationSnapshot(snapshot)
                    .build(),
                null,
                ConversationPersistencePolicy.READ_ONLY
            )
        );

        PipelineContext updated =
            new ConversationEnrichmentStep(service, properties)
                .process(context);

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult().getErrorCode())
            .isEqualTo("ACCESS_DENIED");
        verifyNoInteractions(service);
    }

    @Test
    void shouldUseBoundConversationOwnerInsteadOfTrustedDomainSubject() {
        ChatSessionService service = mock(ChatSessionService.class);
        when(service.getConversationMessages("conversation-1", "support-agent-7"))
            .thenReturn(List.of());
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties
        );
        TrustedExecutionContext trustedContext = new TrustedExecutionContext(
            new ExecutionPrincipal(
                "service-principal",
                ExecutionPrincipalType.SERVICE
            ),
            new ExecutionSubjectRef("account", "account-42"),
            ExecutionSource.APPLICATION,
            "tenant-1",
            "resolver-app",
            Set.of("specialist:account-resolver@1"),
            "correlation-1",
            null
        );
        PipelineContext context = PipelineContext.from(
            new OrchestrationRequest(
                "Specialist envelope",
                OrchestrationContext.builder()
                    .userId("support-agent-7")
                    .conversationId("conversation-1")
                    .build(),
                trustedContext,
                ConversationPersistencePolicy.READ_ONLY
            )
        );

        step.process(context);

        verify(service).getConversationMessages(
            "conversation-1",
            "support-agent-7"
        );
    }

    @Test
    void shouldTerminateWhenAccessDenied() {
        ChatSessionService service = mock(ChatSessionService.class);
        when(service.getConversationMessages(anyString(), anyString())).thenThrow(new ChatSessionAccessDeniedException("denied"));
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties
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

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        properties.setPinnedTargetReuseWindowTurns(3);

        ConversationEnrichmentStep step = new ConversationEnrichmentStep(
            service,
            properties
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
