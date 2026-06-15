package ai.fabric.chat.pipeline;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.chat.domain.ChatSession;
import ai.fabric.chat.domain.ChatTurn;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkingSetTargetSeedingStepTest {

    @Test
    void shouldNotSeedWhenNoIntentRequiresTargetResolution() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        WorkingSetTargetSeedingStep step = new WorkingSetTargetSeedingStep(service, properties);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("info")
            .requiresTargetResolution(false)
            .build();

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("compare", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getResolvedTargets()).isEmpty();
        verify(service, never()).getSession(anyString(), anyString());
    }

    @Test
    void shouldSeedFromLatestWorkingSetWhenIntentRequiresTargetResolution() {
        ChatSessionService service = mock(ChatSessionService.class);

        ChatTurn turn = ChatTurn.builder()
            .userQuery("q")
            .aiResponse("a")
            .timestamp(LocalDateTime.now())
            .turnMetadata(Map.of(
                "_workingSet", Map.of(
                    "topDocumentRefs", List.of(
                        Map.of("id", "2", "vectorSpace", "product", "metadata", Map.of("sku", "ELEC-PHONE-002"))
                    )
                )
            ))
            .build();

        ChatSession session = ChatSession.builder()
            .id("conv-1")
            .ownerId("user-1")
            .turns(List.of(turn))
            .createdAt(LocalDateTime.now())
            .lastInteractionAt(LocalDateTime.now())
            .build();
        when(service.getSession("conv-1", "user-1")).thenReturn(session);

        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        WorkingSetTargetSeedingStep step = new WorkingSetTargetSeedingStep(service, properties);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare")
            .requiresTargetResolution(true)
            .build();

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("compare both", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getResolvedTargets()).isNotEmpty();
        assertThat(updated.getResolvedTargets().getFirst().getSource()).isEqualTo(ResolvedTargetSource.WORKING_SET);
        assertThat(updated.getMetadata()).containsKey("workingSetTargetSeeding");
        verify(service).getSession("conv-1", "user-1");
    }

    @Test
    void shouldNotSeedWhenRequestAttachmentsExist() {
        ChatSessionService service = mock(ChatSessionService.class);
        ChatSessionProperties properties = new ChatSessionProperties();
        properties.setEnabled(true);

        WorkingSetTargetSeedingStep step = new WorkingSetTargetSeedingStep(service, properties);

        Intent intent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("add_to_cart")
            .requiresTargetResolution(true)
            .build();

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .id("att-1")
                .vectorSpace("product")
                .build()))
            .build();

        PipelineContext context = PipelineContext.from("add it", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getResolvedTargets()).isEmpty();
        verify(service, never()).getSession(anyString(), anyString());
    }
}
