package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.SessionStatus;
import ai.fabric.chat.resolver.CompoundConfirmationResolver;
import ai.fabric.chat.resolver.SingleConfirmationPositiveResolver;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.chat.spi.IntentResolver;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConfirmationResolutionStepTest {

    @Test
    void shouldResolvePositiveConfirmationIntoActionAndMarkConfirmed() {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        when(chatSessionService.getSession(anyString(), anyString())).thenReturn(ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .status(SessionStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .sessionMetadata(Map.of())
            .build());

        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        PendingAction pending = new PendingAction(
            "create_purchase_order",
            Map.of("sku", "ELEC-LAPTOP-001", "quantity", 1),
            "Create purchase order for 1 × ELEC-LAPTOP-001?",
            Instant.now(),
            Map.of("sku", List.of("ELEC-LAPTOP-001"))
        );
        when(pendingActionStore.peekPendingAction("conv-1", "user-1")).thenReturn(Optional.of(pending));
        when(pendingActionStore.popPendingAction("conv-1", "user-1")).thenReturn(Optional.of(pending));

        IntentResolver resolver = new SingleConfirmationPositiveResolver(pendingActionStore);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        ConfirmationResolutionStep step = new ConfirmationResolutionStep(chatSessionService, properties, List.of(resolver));

        OrchestrationContext orch = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        MultiIntentResponse extracted = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).confidence(1.0d).build()))
            .build();

        PipelineContext ctx = PipelineContext.from("structured confirmation turn", orch).toBuilder()
            .intentResponse(extracted)
            .build();

        PipelineContext resolved = step.process(ctx);
        assertThat(resolved.getIntentResponse()).isNotNull();
        assertThat(resolved.getIntentResponse().getIntents()).hasSize(1);
        assertThat(resolved.getIntentResponse().getIntents().getFirst().getType()).isEqualTo(IntentType.ACTION);
        assertThat(resolved.getIntentResponse().getIntents().getFirst().getAction()).isEqualTo("create_purchase_order");
        assertThat(resolved.isActionConfirmed("create_purchase_order")).isTrue();
        assertThat(resolved.getMetadata())
            .containsEntry(PendingAction.TRUSTED_EVIDENCE_METADATA_KEY, Map.of("sku", List.of("ELEC-LAPTOP-001")));
    }

    @Test
    void shouldNotResolveExpiredSinglePositiveConfirmationIntoAction() {
        ChatSessionService chatSessionService = sessionService("conv-1", "user-1");
        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        PendingAction expired = pending("create_purchase_order", Instant.now().minusSeconds(600));
        when(pendingActionStore.peekPendingAction("conv-1", "user-1")).thenReturn(Optional.of(expired));

        ConfirmationResolutionStep step = new ConfirmationResolutionStep(
            chatSessionService,
            enabledProperties(),
            List.of(new SingleConfirmationPositiveResolver(pendingActionStore))
        );

        MultiIntentResponse extracted = MultiIntentResponse.builder()
            .intents(List.of(Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).confidence(1.0d).build()))
            .build();
        PipelineContext ctx = PipelineContext.from("structured confirmation turn", context("conv-1", "user-1")).toBuilder()
            .intentResponse(extracted)
            .build();

        PipelineContext resolved = step.process(ctx);

        assertThat(resolved.getIntentResponse()).isSameAs(extracted);
        assertThat(resolved.getConfirmedActions()).isEmpty();
        verify(pendingActionStore, never()).popPendingAction("conv-1", "user-1");
    }

    @Test
    void shouldNotResolveExpiredCompoundConfirmationIntoAction() {
        ChatSessionService chatSessionService = sessionService("conv-2", "user-1");
        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        PendingAction expired = pending("create_purchase_order", Instant.now().minusSeconds(600));
        when(pendingActionStore.peekPendingAction("conv-2", "user-1")).thenReturn(Optional.of(expired));

        ConfirmationResolutionStep step = new ConfirmationResolutionStep(
            chatSessionService,
            enabledProperties(),
            List.of(new CompoundConfirmationResolver(pendingActionStore))
        );

        MultiIntentResponse extracted = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).confidence(1.0d).build(),
                Intent.builder().type(IntentType.INFORMATION).intent("show_status").confidence(0.8d).build()
            ))
            .build();
        PipelineContext ctx = PipelineContext.from("expired compound confirmation turn", context("conv-2", "user-1")).toBuilder()
            .intentResponse(extracted)
            .build();

        PipelineContext resolved = step.process(ctx);

        assertThat(resolved.getIntentResponse()).isSameAs(extracted);
        assertThat(resolved.getConfirmedActions()).isEmpty();
        verify(pendingActionStore, never()).popPendingAction("conv-2", "user-1");
    }

    @Test
    void shouldResolveCompoundStructuredConfirmationAndFollowUpIntent() {
        ChatSessionService chatSessionService = sessionService("conv-5", "user-1");
        PendingActionStore pendingActionStore = mock(PendingActionStore.class);
        PendingAction pending = pending("create_purchase_order", Instant.now());
        when(pendingActionStore.peekPendingAction("conv-5", "user-1")).thenReturn(Optional.of(pending));
        when(pendingActionStore.popPendingAction("conv-5", "user-1")).thenReturn(Optional.of(pending));

        ConfirmationResolutionStep step = new ConfirmationResolutionStep(
            chatSessionService,
            enabledProperties(),
            List.of(new CompoundConfirmationResolver(pendingActionStore))
        );

        Intent followUp = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("show_status")
            .confidence(0.8d)
            .build();
        MultiIntentResponse extracted = MultiIntentResponse.builder()
            .intents(List.of(
                Intent.builder().type(IntentType.CONFIRMATION_POSITIVE).confidence(1.0d).build(),
                followUp
            ))
            .build();
        PipelineContext ctx = PipelineContext.from("compound confirmation turn", context("conv-5", "user-1")).toBuilder()
            .intentResponse(extracted)
            .build();

        PipelineContext resolved = step.process(ctx);

        assertThat(resolved.getIntentResponse().getIntents()).hasSize(2);
        assertThat(resolved.getIntentResponse().getIntents().getFirst().getType()).isEqualTo(IntentType.ACTION);
        assertThat(resolved.getIntentResponse().getIntents().getFirst().getAction()).isEqualTo("create_purchase_order");
        assertThat(resolved.getIntentResponse().getIntents().get(1)).isSameAs(followUp);
        assertThat(resolved.isActionConfirmed("create_purchase_order")).isTrue();
    }

    private ChatSessionService sessionService(String conversationId, String ownerId) {
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        when(chatSessionService.getSession(anyString(), anyString())).thenReturn(ChatSession.builder()
            .id(conversationId)
            .ownerId(ownerId)
            .status(SessionStatus.ACTIVE)
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .sessionMetadata(Map.of())
            .build());
        return chatSessionService;
    }

    private ChatSessionProperties enabledProperties() {
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);
        return properties;
    }

    private OrchestrationContext context(String conversationId, String userId) {
        return OrchestrationContext.builder()
            .userId(userId)
            .conversationId(conversationId)
            .build();
    }

    private PendingAction pending(String action, Instant createdAt) {
        return new PendingAction(
            action,
            Map.of("sku", "ELEC-LAPTOP-001", "quantity", 1),
            "Create purchase order?",
            createdAt,
            Map.of("sku", List.of("ELEC-LAPTOP-001"))
        );
    }
}
