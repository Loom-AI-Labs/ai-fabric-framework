package ai.fabric.intent.orchestration.pipeline.steps;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.PendingAction;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PendingActionPromptAugmentationStepTest {

    @Test
    void shouldInjectPendingActionBlockIntoPinnedTargetsContext() {
        InMemoryPendingActionStore store = new InMemoryPendingActionStore();
        store.pushPendingAction(
            "conv-1",
            "user-1",
            new PendingAction("add_to_cart", Map.of("sku", "SKU-1"), "Add item to cart?", Instant.now())
        );

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user-1")
            .conversationId("conv-1")
            .build();

        PipelineContext context = PipelineContext.from("confirmation turn", orchContext);

        PendingActionPromptAugmentationStep step = new PendingActionPromptAugmentationStep(store);
        PipelineContext updated = step.process(context);

        assertThat(updated.getPinnedTargetsContext())
            .contains("PENDING ACTION")
            .contains("action=add_to_cart")
            .contains("Decide from the whole user turn whether the user intends to approve, reject, or ignore")
            .contains("classify the new request normally");
        assertThat(updated.getMetadata()).containsKey("pendingActionPrompt");
    }
}
