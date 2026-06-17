package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.Intent;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InformationIntentPlanningSupportTest {

    @Test
    void shouldForceRetrievalWhenActiveTargetsArePresent() {
        Intent intent = Intent.builder()
            .intent("compare reviews")
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .build();
        OrchestrationContext context = OrchestrationContext.forUser("user-1");
        PipelineContext pipelineContext = PipelineContext.from("compare reviews", context)
            .toBuilder()
            .orchestrationPolicy(policy(capabilities(true, false, false)))
            .resolvedTargets(List.of(target("product", ResolvedTargetSource.REQUEST_ATTACHMENTS)))
            .build();

        InformationIntentPlanningSupport.Plan plan = InformationIntentPlanningSupport.plan(
            intent,
            context,
            pipelineContext,
            new OrchestrationProperties(),
            false
        );

        assertThat(plan.requiresRetrieval()).isTrue();
        assertThat(plan.needsGeneration()).isFalse();
        assertThat(plan.metadata())
            .containsEntry("retrievalForced", true)
            .containsEntry("retrievalForcedReason", "ACTIVE_TARGETS")
            .containsEntry("forceRetrievalWhenTargetsPresentEnabled", true);
    }

    @Test
    void shouldSkipRetrievalWhenPinnedTargetsCoverTheRequestInDeterministicMode() {
        Intent intent = Intent.builder()
            .intent("summarize selected product")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .requiresTargetResolution(true)
            .vectorSpace("product")
            .build();
        OrchestrationContext context = OrchestrationContext.forUser("user-1");
        PipelineContext pipelineContext = PipelineContext.from("summarize selected product", context)
            .toBuilder()
            .orchestrationPolicy(policy(capabilities(false, false, true)))
            .resolvedTargets(List.of(target("product", ResolvedTargetSource.WORKING_SET)))
            .build();

        InformationIntentPlanningSupport.Plan plan = InformationIntentPlanningSupport.plan(
            intent,
            context,
            pipelineContext,
            new OrchestrationProperties(),
            true
        );

        assertThat(plan.requiresRetrieval()).isFalse();
        assertThat(plan.needsGeneration()).isTrue();
        assertThat(plan.metadata())
            .containsEntry("retrievalSkipped", true)
            .containsEntry("retrievalSkipReason", "PINNED_TARGETS");
    }

    @Test
    void shouldEnableGenerationWhenAlwaysGenerateInformationIsConfigured() {
        Intent intent = Intent.builder()
            .intent("search policy")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .build();
        OrchestrationContext context = OrchestrationContext.forUser("user-1");
        PipelineContext pipelineContext = PipelineContext.from("search policy", context);
        OrchestrationProperties properties = new OrchestrationProperties();
        properties.setAlwaysGenerateInformation(true);

        InformationIntentPlanningSupport.Plan plan = InformationIntentPlanningSupport.plan(
            intent,
            context,
            pipelineContext,
            properties,
            false
        );

        assertThat(plan.requiresRetrieval()).isTrue();
        assertThat(plan.needsGeneration()).isTrue();
        assertThat(plan.metadata()).containsEntry("requiresGeneration", true);
    }

    @Test
    void shouldPreferOptimizedQueryForRetrievalAndEffectiveQueryForGeneration() {
        Intent intent = Intent.builder()
            .intent("raw query")
            .optimizedQuery("optimized query")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .build();
        OrchestrationContext context = OrchestrationContext.forUser("user-1");
        PipelineContext pipelineContext = PipelineContext.from("original query", context)
            .toBuilder()
            .processedQuery("redacted query")
            .detectedPiiTypes(List.of("EMAIL"))
            .build();

        InformationIntentPlanningSupport.Plan plan = InformationIntentPlanningSupport.plan(
            intent,
            context,
            pipelineContext,
            new OrchestrationProperties(),
            false
        );

        assertThat(plan.retrievalBaseQuery()).isEqualTo("optimized query");
        assertThat(plan.generationQuery()).isEqualTo("redacted query");
        assertThat(plan.metadata())
            .containsEntry("optimizedQuery", "optimized query")
            .containsEntry("piiProcessed", true)
            .containsEntry("piiDetectedTypes", List.of("EMAIL"));
    }

    private OrchestrationPolicy policy(OrchestrationPolicy.OrchestrationCapabilities capabilities) {
        return new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "navigator",
            null,
            null,
            capabilities,
            OrchestrationPolicy.RagBudgets.defaults()
        );
    }

    private OrchestrationPolicy.OrchestrationCapabilities capabilities(boolean forceRetrievalWhenTargetsPresent,
                                                                      boolean forceRetrievalConsiderStoredTargets,
                                                                      boolean minimizeRagWhenPinnedTargetsCoverRequest) {
        return new OrchestrationPolicy.OrchestrationCapabilities(
            true,
            true,
            true,
            true,
            false,
            false,
            true,
            false,
            false,
            minimizeRagWhenPinnedTargetsCoverRequest,
            forceRetrievalWhenTargetsPresent,
            forceRetrievalConsiderStoredTargets
        );
    }

    private ResolvedTarget target(String vectorSpace, ResolvedTargetSource source) {
        return ResolvedTarget.builder()
            .id("target-1")
            .vectorSpace(vectorSpace)
            .contentText("selected target")
            .source(source)
            .build();
    }
}
