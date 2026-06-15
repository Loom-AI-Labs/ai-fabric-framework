package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepEmbeddingQueryExpansionTest {

    @Test
    void shouldBuildSemanticEmbeddingQueryFromOptimizedQueryAndPinnedTargets() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any())).thenReturn(RAGResponse.builder().success(true).documents(List.of()).context("ctx").build());

        OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
        orchestrationProperties.getRag().getTargetHint().setEnabled(true);
        orchestrationProperties.getRag().getTargetHint().setMetadataKeysAllowlist(List.of("sku"));

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            mock(AICoreService.class),
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            new VectorSpaceRoutingProperties(),
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            orchestrationProperties,
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("reviews")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .requiresTargetResolution(true)
            .optimizedQuery("negative reviews")
            .vectorSpace("review")
            .build();

        PipelineContext context = PipelineContext.from("any negative reviews on them?", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(intent))
                .build())
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("30")
                .vectorSpace("product")
                .contentText("Bose Pro Headphones")
                .metadata(Map.of("sku", "SKU-BOS-20002"))
                .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
                .build()))
            .build();

        step.process(context);

        ArgumentCaptor<ai.fabric.dto.RAGRequest> captor = ArgumentCaptor.forClass(ai.fabric.dto.RAGRequest.class);
        verify(ragProvider).performRag(captor.capture());

        ai.fabric.dto.RAGRequest request = captor.getValue();
        assertThat(request.getQuery()).isEqualTo("negative reviews");

        Map<String, Object> metadata = request.getMetadata();
        assertThat(metadata).containsEntry("userQuery", "any negative reviews on them?");
        assertThat(metadata).containsKey("embeddingQuery");
        assertThat(String.valueOf(metadata.get("embeddingQuery")))
            .contains("negative reviews")
            .contains("Targets:")
            .contains("sku=SKU-BOS-20002");

        assertThat(request.getQuery()).doesNotContain("Targets:");
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
