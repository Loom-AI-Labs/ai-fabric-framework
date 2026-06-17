package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InformationRetrievalQuerySupportTest {

    @Test
    void shouldForceTargetResolutionAndRecordEmbeddingMetadataInDeepMode() {
        OrchestrationProperties properties = new OrchestrationProperties();
        properties.getRag().getTargetHint().setEnabled(true);
        properties.getRag().getTargetHint().setMetadataKeysAllowlist(List.of("sku"));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("reviews")
            .requiresTargetResolution(false)
            .build();
        PipelineContext context = contextWithTarget();
        Map<String, Object> metadata = new LinkedHashMap<>();

        InformationRetrievalQuerySupport.PreparedRetrievalQuery prepared =
            InformationRetrievalQuerySupport.prepare(
                intent,
                context,
                properties,
                true,
                true,
                "optimized reviews",
                "processed reviews",
                "optimized reviews",
                "processed reviews",
                metadata
            );

        assertThat(prepared.forcedTargetResolution()).isTrue();
        assertThat(intent.getRequiresTargetResolution()).isTrue();
        assertThat(prepared.retrievalQuery()).isEqualTo("optimized reviews");
        assertThat(prepared.embedding().embeddingQuery()).contains("Targets:");
        assertThat(metadata)
            .containsEntry("requiresTargetResolutionForced", true)
            .containsEntry("targetHintEnabled", true)
            .containsEntry("targetHintApplied", true)
            .containsEntry("targetHintTargetsUsed", 1)
            .containsEntry("userQuery", "processed reviews");
        assertThat((String) metadata.get("embeddingQuery")).contains("sku=SKU-1");
    }

    @Test
    void shouldUseOptimizedQueryAsAdvancedDecisionQueryWhenPresent() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("raw")
            .optimizedQuery("optimized")
            .build();
        Map<String, Object> metadata = new LinkedHashMap<>();

        InformationRetrievalQuerySupport.PreparedRetrievalQuery prepared =
            InformationRetrievalQuerySupport.prepare(
                intent,
                PipelineContext.from("original", OrchestrationContext.forUser("user-1")),
                new OrchestrationProperties(),
                false,
                false,
                "optimized",
                "processed",
                "optimized",
                "processed",
                metadata
            );

        assertThat(prepared.advancedDecisionQuery()).isEqualTo("optimized");
        assertThat(metadata).containsEntry("embeddingQuery", "optimized");
    }

    @Test
    void shouldFallBackToOriginalQueryForAdvancedDecisionWhenOptimizedQueryIsMissing() {
        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("raw")
            .build();

        InformationRetrievalQuerySupport.PreparedRetrievalQuery prepared =
            InformationRetrievalQuerySupport.prepare(
                intent,
                PipelineContext.from("original question", OrchestrationContext.forUser("user-1")),
                new OrchestrationProperties(),
                false,
                false,
                null,
                "processed question",
                "processed question",
                "processed question",
                new LinkedHashMap<>()
            );

        assertThat(prepared.advancedDecisionQuery()).isEqualTo("original question");
    }

    private PipelineContext contextWithTarget() {
        return PipelineContext.from("reviews", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("target-1")
                .vectorSpace("product")
                .contentText("Product One")
                .metadata(Map.of("sku", "SKU-1"))
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .orchestrationPolicy(new OrchestrationPolicy(
                OrchestrationProfile.DEFAULT,
                "navigator_deep",
                null,
                null,
                null,
                null
            ))
            .build();
    }
}
