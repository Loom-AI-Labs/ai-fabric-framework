package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.LlmPurpose;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.RAGRequest;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.attachment.NormalizedAttachment;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTarget;
import com.ai.infrastructure.intent.orchestration.targets.ResolvedTargetSource;
import com.ai.infrastructure.intent.vectorspace.RankBasedMerger;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.spi.AdvancedRAGProvider;
import com.ai.infrastructure.spi.RAGProvider;
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
