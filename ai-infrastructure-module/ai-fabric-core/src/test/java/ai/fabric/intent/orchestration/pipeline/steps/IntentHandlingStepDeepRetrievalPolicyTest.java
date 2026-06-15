package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.attachment.NormalizedAttachment;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepDeepRetrievalPolicyTest {

    @Test
    void shouldNotSkipRetrievalWhenMinimizeRagIsDisabled() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any())).thenReturn(RAGResponse.builder()
            .documents(List.of())
            .context("context")
            .success(true)
            .build());

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder().content("answer").build()
        );

        IntentHandlingStep step = newStep(ragProvider, aiCoreService);

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "navigator_deep",
            null,
            OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE,
            new OrchestrationPolicy.OrchestrationCapabilities(
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                false
            ),
            null
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .vectorSpace("product")
            .build();

        PipelineContext context = baseContext(policy, intent);

        step.process(context);

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRAGQuery(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getMetadata())
            .containsEntry("minimizeRagHeuristicEnabled", false);
        assertThat(requestCaptor.getValue().getMetadata())
            .doesNotContainKey("retrievalSkipped");
    }

    @Test
    void shouldForceRetrievalWhenTargetsArePresentEvenIfLlmSaysNo() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any())).thenReturn(RAGResponse.builder()
            .documents(List.of())
            .context("context")
            .success(true)
            .build());

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder().content("answer").build()
        );

        IntentHandlingStep step = newStep(ragProvider, aiCoreService);

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.PRODUCTION_CHAT,
            "navigator_deep",
            null,
            OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE,
            new OrchestrationPolicy.OrchestrationCapabilities(
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                false,
                false,
                false,
                true,
                false
            ),
            null
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("compare_reviews")
            .requiresRetrieval(false)
            .requiresGeneration(false)
            .vectorSpace("product")
            .build();

        PipelineContext context = baseContext(policy, intent);

        step.process(context);

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRAGQuery(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getMetadata())
            .containsEntry("retrievalForced", true)
            .containsEntry("retrievalForcedReason", "ACTIVE_TARGETS");
    }

    private PipelineContext baseContext(OrchestrationPolicy policy, Intent intent) {
        OrchestrationContext orchContext = OrchestrationContext.builder()
            .userId("user")
            .attachmentsNormalized(List.of(NormalizedAttachment.builder()
                .id("30")
                .vectorSpace("product")
                .contentText("sony wh-1000xm5")
                .metadata(Map.of("sku", "SKU-SON-57834"))
                .build()))
            .build();

        ResolvedTarget resolvedTarget = ResolvedTarget.builder()
            .id("30")
            .vectorSpace("product")
            .contentText("sony wh-1000xm5")
            .metadata(Map.of("sku", "SKU-SON-57834"))
            .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
            .build();

        return PipelineContext.from("compare reviews", orchContext)
            .toBuilder()
            .orchestrationPolicy(policy)
            .resolvedTargets(List.of(resolvedTarget))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();
    }

    private IntentHandlingStep newStep(RAGProvider ragProvider, AICoreService aiCoreService) {
        AIServiceConfig aiServiceConfig = new AIServiceConfig();
        aiServiceConfig.getFeatures().setEnableGeneration(true);

        return new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            aiServiceConfig,
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );
    }

    private <T> ObjectProvider<T> providerOf(T value) {
        @SuppressWarnings("unchecked")
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    private PromptTemplateResolver promptTemplateResolver() {
        return new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            new PromptBundleProperties()
        );
    }
}
