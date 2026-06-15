package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.SessionStatus;
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

        PipelineContext ctx = PipelineContext.from("yes", orch).toBuilder()
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
}
