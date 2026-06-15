package ai.fabric.intent.orchestration.rag;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingQueryComposerTest {

    @Test
    void shouldReturnBaseQueryWhenTargetHintDisabled() {
        OrchestrationProperties props = new OrchestrationProperties();
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .requiresTargetResolution(true)
            .build();

        PipelineContext ctx = PipelineContext.builder()
            .originalQuery("q")
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("30")
                .vectorSpace("product")
                .contentText("Bose Pro Headphones")
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .orchestrationPolicy(new OrchestrationPolicy(OrchestrationProfile.DEFAULT, "navigator_deep", null, null, null, null))
            .build();

        EmbeddingQueryComposer.Result result = EmbeddingQueryComposer.compose("any negative reviews?", intent, ctx, props);
        assertThat(result.embeddingQuery()).isEqualTo("any negative reviews?");
        assertThat(result.targetHintEnabled()).isFalse();
        assertThat(result.targetHintApplied()).isFalse();
    }

    @Test
    void shouldAppendTargetsWhenEnabledAndRequiresTargetResolution() {
        OrchestrationProperties props = new OrchestrationProperties();
        props.getRag().getTargetHint().setEnabled(true);
        props.getRag().getTargetHint().setMetadataKeysAllowlist(List.of("sku"));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .requiresTargetResolution(true)
            .build();

        PipelineContext ctx = PipelineContext.builder()
            .originalQuery("q")
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("30")
                .vectorSpace("product")
                .contentText("Bose Pro Headphones")
                .metadata(Map.of("sku", "SKU-BOS-20002", "email", "test@example.com"))
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .orchestrationPolicy(new OrchestrationPolicy(OrchestrationProfile.DEFAULT, "navigator_deep", null, null, null, null))
            .build();

        EmbeddingQueryComposer.Result result = EmbeddingQueryComposer.compose("any negative reviews?", intent, ctx, props);

        assertThat(result.targetHintEnabled()).isTrue();
        assertThat(result.targetHintApplied()).isTrue();
        assertThat(result.targetHintTargetsUsed()).isEqualTo(1);
        assertThat(result.embeddingQuery()).contains("Targets:");
        assertThat(result.embeddingQuery()).contains("vectorSpace=product");
        assertThat(result.embeddingQuery()).contains("id=30");
        assertThat(result.embeddingQuery()).contains("sku=SKU-BOS-20002");
        assertThat(result.embeddingQuery()).contains("text=\"Bose Pro Headphones\"");
        assertThat(result.embeddingQuery()).doesNotContain("email=");
        assertThat(result.embeddingQuery()).doesNotContain("@");
    }

    @Test
    void shouldRespectModeOverrideWhenGlobalDisabled() {
        OrchestrationProperties props = new OrchestrationProperties();
        props.getRag().getTargetHint().setEnabled(false);
        props.getRag().getTargetHint().setMetadataKeysAllowlist(List.of("sku"));

        OrchestrationProperties.ModeOverrides overrides = new OrchestrationProperties.ModeOverrides();
        overrides.setRagTargetHintEnabled(true);
        props.getModes().put("navigator_deep", overrides);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .requiresTargetResolution(true)
            .build();

        PipelineContext ctx = PipelineContext.builder()
            .originalQuery("q")
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("30")
                .vectorSpace("product")
                .contentText("Bose Pro Headphones")
                .metadata(Map.of("sku", "SKU-BOS-20002"))
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .orchestrationPolicy(new OrchestrationPolicy(OrchestrationProfile.DEFAULT, "navigator_deep", null, null, null, null))
            .build();

        EmbeddingQueryComposer.Result result = EmbeddingQueryComposer.compose("any negative reviews?", intent, ctx, props);
        assertThat(result.targetHintEnabled()).isTrue();
        assertThat(result.targetHintApplied()).isTrue();
    }

    @Test
    void shouldAllowAllowlistedRootFieldsWhenMetadataIsEmpty() {
        OrchestrationProperties props = new OrchestrationProperties();
        props.getRag().getTargetHint().setEnabled(true);
        props.getRag().getTargetHint().setIncludeId(false);
        props.getRag().getTargetHint().setIncludeVectorSpace(false);
        props.getRag().getTargetHint().setIncludeContentText(false);
        props.getRag().getTargetHint().setMetadataKeysAllowlist(List.of("id", "type", "text"));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .requiresTargetResolution(true)
            .build();

        PipelineContext ctx = PipelineContext.builder()
            .originalQuery("q")
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("30")
                .vectorSpace("product")
                .contentText("Bose Pro Headphones")
                .metadata(Map.of())
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .orchestrationPolicy(new OrchestrationPolicy(OrchestrationProfile.DEFAULT, "navigator_deep", null, null, null, null))
            .build();

        EmbeddingQueryComposer.Result result = EmbeddingQueryComposer.compose("any negative reviews?", intent, ctx, props);
        assertThat(result.embeddingQuery()).contains("Targets:");
        assertThat(result.embeddingQuery()).contains("id=30");
        assertThat(result.embeddingQuery()).contains("type=product");
        assertThat(result.embeddingQuery()).contains("text=\"Bose Pro Headphones\"");
    }
}
