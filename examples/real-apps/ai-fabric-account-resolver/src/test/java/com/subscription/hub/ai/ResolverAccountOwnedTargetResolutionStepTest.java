package com.subscription.hub.ai;

import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ResolverAccountOwnedTargetResolutionStepTest {

    private final ResolverAccountOwnedTargetResolutionStep step = new ResolverAccountOwnedTargetResolutionStep();

    @Test
    void clearsTargetResolutionForCurrentAccountActions() {
        PipelineContext context = contextWithIntent(Intent.builder()
            .type(IntentType.ACTION)
            .intent("update_address")
            .action("update_address")
            .requiresTargetResolution(true)
            .build());

        PipelineContext updated = step.process(context);

        assertThat(updated.getIntentResponse().getIntents().getFirst().getRequiresTargetResolution()).isFalse();
        assertThat(updated.getMetadata()).containsKey("resolverAccountOwnedTargetResolution");
    }

    @Test
    void usesIntentNameWhenActionNameIsNotPopulatedYet() {
        PipelineContext context = contextWithIntent(Intent.builder()
            .type(IntentType.ACTION)
            .intent("request_refund")
            .requiresTargetResolution(true)
            .build());

        PipelineContext updated = step.process(context);

        assertThat(updated.getIntentResponse().getIntents().getFirst().getRequiresTargetResolution()).isFalse();
    }

    @Test
    void leavesExternalTargetDependentActionsUnchanged() {
        PipelineContext context = contextWithIntent(Intent.builder()
            .type(IntentType.ACTION)
            .intent("annotate_document")
            .action("annotate_document")
            .requiresTargetResolution(true)
            .build());

        PipelineContext updated = step.process(context);

        assertThat(updated).isSameAs(context);
        assertThat(updated.getIntentResponse().getIntents().getFirst().getRequiresTargetResolution()).isTrue();
    }

    private PipelineContext contextWithIntent(Intent intent) {
        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("93")
            .sessionId("resolver-test")
            .build();
        return PipelineContext.from("update my billing address", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder()
                .intents(java.util.List.of(intent))
                .orchestrationStrategy("DIRECT_ACTION")
                .build())
            .build();
    }
}
