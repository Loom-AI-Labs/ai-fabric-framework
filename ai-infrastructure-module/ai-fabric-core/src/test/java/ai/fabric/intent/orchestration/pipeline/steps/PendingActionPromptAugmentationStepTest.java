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
    void shouldProvidePendingStateAsTrustedInstructionsWithoutConsumingIt() {
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

        assertThat(updated.getIntentExtractionSystemInstructions())
            .contains("PENDING ACTION")
            .contains("action=add_to_cart")
            .contains("Decide from the whole user turn whether the user intends to approve, reject, or ignore")
            .contains("classify the new request normally");
        assertThat(updated.getPinnedTargetsContext()).isNull();
        assertThat(updated.isPendingActionStateObserved()).isTrue();
        assertThat(store.peekPendingAction("conv-1", "user-1")).isPresent();
        assertThat(updated.getMetadata()).containsKey("pendingActionPrompt");
    }

    @Test
    void shouldMakeAnEmptyStoreExplicitAndPreserveExistingContext() {
        var context = PipelineContext.from("yes please do", OrchestrationContext.builder()
            .userId("user-1").conversationId("conv-1").build()).toBuilder()
            .pinnedTargetsContext("Approved record")
            .intentExtractionSystemInstructions("Existing trusted instruction").build();
        var updated = new PendingActionPromptAugmentationStep(new InMemoryPendingActionStore()).process(context);
        assertThat(updated.getIntentExtractionSystemInstructions())
            .startsWith("Existing trusted instruction")
            .contains("pendingActionPresent=false", "informational continuation", "self-contained optimizedQuery",
                "set requiresRetrieval=false and leave vectorSpace empty",
                "If fresh indexed evidence is actually needed, keep requiresRetrieval=true",
                "Historical content is not current evidence or permission");
        assertThat(updated.getPinnedTargetsContext()).isEqualTo("Approved record");
        assertThat(updated.isPendingActionStateObserved()).isTrue();
        assertThat(updated.getObservedPendingAction()).isNull();
    }

    @Test
    void anotherOwnersPendingActionCannotBecomeThisUsersConfirmation() {
        var store = new InMemoryPendingActionStore();
        store.pushPendingAction("conv-1", "other-owner", new PendingAction("submit_request",
            Map.of("email", "private@example.test"), "Private description", Instant.now()));
        var context = PipelineContext.from("yes", OrchestrationContext.builder()
            .userId("user-1").conversationId("conv-1").build());
        var updated = new PendingActionPromptAugmentationStep(store).process(context);
        assertThat(updated.getIntentExtractionSystemInstructions()).contains("pendingActionPresent=false")
            .doesNotContain("private@example.test", "Private description", "submit_request");
        assertThat(store.peekPendingAction("conv-1", "other-owner")).isPresent();
    }

    @Test
    void unavailableStateIsNotReportedAsAnEmptyStore() {
        var context = PipelineContext.from("yes", OrchestrationContext.forUser("user-1"));
        assertThat(new PendingActionPromptAugmentationStep(new InMemoryPendingActionStore()).process(context)).isSameAs(context);
        assertThat(context.isPendingActionStateObserved()).isFalse();
    }
}
