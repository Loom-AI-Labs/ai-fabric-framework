package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.intent.KnowledgeBaseOverview;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.vectorspace.RoutingResult;
import ai.fabric.intent.vectorspace.RoutingStrategy;
import ai.fabric.intent.vectorspace.VectorSpaceRouter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorSpaceResolutionStepTest {

    @Test
    void shouldSkipWhenVectorSpaceAlreadyPresent() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace("policies")
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("policies");
        verify(router, never()).route(any(), anyString());
    }

    @Test
    void shouldFallbackToFanOutWhenVectorSpaceNotAvailable() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);

        KnowledgeBaseOverviewService overviewService = mock(KnowledgeBaseOverviewService.class);
        when(overviewService.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .entityTypes(List.of("faq", "policies"))
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf(overviewService)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("search_products")
            .requiresRetrieval(true)
            .vectorSpace("commerce")
            .build();

        PipelineContext context = PipelineContext.from("smartphone", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("faq,policies");
        assertThat(updated.getMetadata()).containsKey("vectorSpaceRouting");
        verify(router, never()).route(any(), anyString());
    }

    @Test
    void shouldFallbackToAllowlistWhenVectorSpaceInvalidAndAllowlistPresent() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);

        KnowledgeBaseOverviewService overviewService = mock(KnowledgeBaseOverviewService.class);
        when(overviewService.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .entityTypes(List.of("product", "review", "policy"))
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf(overviewService)
        );

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "executor",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.RagBudgets(true, null, null, null, null, null, List.of("policy"))
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .requiresRetrieval(true)
            .vectorSpace("policies")
            .build();

        PipelineContext context = PipelineContext.from("refund policy", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("policy");
        assertThat(updated.getMetadata()).containsKey("vectorSpaceRouting");
        verify(router, never()).route(any(), anyString());
    }

    @Test
    void shouldPreferRequestContextVectorSpaceHintOverInvalidExtractedVectorSpace() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);

        KnowledgeBaseOverviewService overviewService = mock(KnowledgeBaseOverviewService.class);
        when(overviewService.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .entityTypes(List.of("primary-docs", "reference-docs"))
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf(overviewService)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("find_onboarding_checklist")
            .requiresRetrieval(true)
            .vectorSpace("wrong-space")
            .build();

        OrchestrationContext orchestrationContext = OrchestrationContext.builder()
            .userId("user")
            .metadata(Map.of(
                OrchestrationContextMetadataKeys.RAG_PREFERRED_VECTOR_SPACES,
                List.of("primary-docs")
            ))
            .build();
        PipelineContext context = PipelineContext.from("Find the onboarding checklist", orchestrationContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("primary-docs");
        assertThat(updated.getMetadata()).containsKey("vectorSpaceRouting");
        verify(router, never()).route(any(), anyString());
    }

    @Test
    void shouldSkipWhenIntentDoesNotRequireRetrieval() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .requiresRetrieval(false)
            .vectorSpace(null)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isNull();
        verify(router, never()).route(any(), anyString());
    }

    @Test
    void shouldResolveSingleVectorSpace() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        when(router.route(any(), anyString())).thenReturn(RoutingResult.builder()
            .success(true)
            .vectorSpace("policies")
            .strategy(RoutingStrategy.HEURISTIC)
            .confidence(0.5d)
            .rationale("test")
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace(null)
            .requiresRetrieval(true)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("policies");
        assertThat(updated.getMetadata()).containsKey("vectorSpaceRouting");
    }

    @Test
    void shouldResolveFanOutIntoCommaSeparatedVectorSpace() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        when(router.route(any(), anyString())).thenReturn(RoutingResult.builder()
            .success(true)
            .candidateSpaces(List.of("faq", "policies"))
            .strategy(RoutingStrategy.FAN_OUT)
            .confidence(0.5d)
            .rationale("fan-out")
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace(null)
            .requiresRetrieval(true)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("faq,policies");
    }

    @Test
    void shouldResolveSingleCandidateFanOutWithoutClarification() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        when(router.route(any(), anyString())).thenReturn(RoutingResult.builder()
            .success(true)
            .candidateSpaces(List.of("faq"))
            .strategy(RoutingStrategy.FAN_OUT)
            .confidence(0.5d)
            .rationale("single candidate")
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace(null)
            .requiresRetrieval(true)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("faq");
    }

    @Test
    void shouldRouteVectorSpaceEvenWhenResolvedTargetsPresent() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        when(router.route(any(), anyString())).thenReturn(RoutingResult.builder()
            .success(true)
            .vectorSpace("product")
            .strategy(RoutingStrategy.HEURISTIC)
            .confidence(0.5d)
            .rationale("test")
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare")
            .requiresRetrieval(true)
            .vectorSpace(null)
            .build();

        ResolvedTarget target = ResolvedTarget.builder()
            .id("1")
            .vectorSpace("product")
            .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
            .build();

        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user")
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .id("1")
                .vectorSpace("product")
                .build()))
            .build();

        PipelineContext context = PipelineContext.from("q", orchContext)
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .resolvedTargets(List.of(target))
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("product");
        verify(router).route(any(), anyString());
    }

    @Test
    void shouldTerminateWithClarificationWhenRoutingFailsWithCandidates() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        when(router.route(any(), anyString())).thenReturn(RoutingResult.builder()
            .success(false)
            .candidateSpaces(List.of("faq", "policies"))
            .strategy(RoutingStrategy.CLARIFICATION)
            .confidence(0.0d)
            .rationale("need clarification")
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace(null)
            .requiresRetrieval(true)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult()).isNotNull();
        assertThat(updated.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(updated.getEarlyTerminationResult().getData()).containsEntry("candidateVectorSpaces", List.of("faq", "policies"));
    }

    @Test
    void shouldTerminateWithClarificationWhenRoutingFailsWithoutCandidates() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        when(router.route(any(), anyString())).thenReturn(RoutingResult.builder()
            .success(false)
            .candidateSpaces(List.of())
            .strategy(RoutingStrategy.CLARIFICATION)
            .confidence(0.0d)
            .rationale("no overview")
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf((KnowledgeBaseOverviewService) null)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace(null)
            .requiresRetrieval(true)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isTrue();
        assertThat(updated.getEarlyTerminationResult()).isNotNull();
        assertThat(updated.getEarlyTerminationResult().getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(updated.getEarlyTerminationResult().getData()).containsEntry("candidateVectorSpaces", List.of());
    }

    @Test
    void shouldFallbackToDeepVectorSpacesWhenRoutingFailsInDeepMode() {
        VectorSpaceRouter router = mock(VectorSpaceRouter.class);
        when(router.route(any(), anyString())).thenReturn(RoutingResult.builder()
            .success(false)
            .candidateSpaces(List.of("faq"))
            .strategy(RoutingStrategy.CLARIFICATION)
            .confidence(0.0d)
            .rationale("need clarification")
            .build());

        KnowledgeBaseOverviewService overviewService = mock(KnowledgeBaseOverviewService.class);
        when(overviewService.getOverview()).thenReturn(KnowledgeBaseOverview.builder()
            .entityTypes(List.of("product", "review", "policy"))
            .documentsByType(Map.of(
                "review", 50L,
                "product", 100L,
                "policy", 10L
            ))
            .build());

        VectorSpaceResolutionStep step = new VectorSpaceResolutionStep(
            router,
            new OrchestrationProperties(),
            new VectorSpaceRoutingProperties(),
            providerOf(overviewService)
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare")
            .requiresRetrieval(true)
            .vectorSpace(null)
            .build();

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "navigator_deep",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            new OrchestrationPolicy.OrchestrationCapabilities(true, true, true, true, false, false, true, false, false, true, false, false),
            new OrchestrationPolicy.RagBudgets(true, 2, null, null, null, null, List.of())
        );

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.isShouldTerminate()).isFalse();
        assertThat(updated.getIntentResponse().getIntents().getFirst().getVectorSpace()).isEqualTo("product,review");
        assertThat(updated.getMetadata()).containsKey("vectorSpaceRouting");
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
