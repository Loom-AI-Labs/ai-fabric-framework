package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.config.PromptBundleProperties;
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
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepRetrievalQueryHintTest {

    @Test
    void shouldAppendRetrievalQueryHintWhenExactlyOneRetrievalIntent() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any())).thenReturn(RAGResponse.builder().success(true).documents(List.of()).context("ctx").build());

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
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent retrievalIntent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("search")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .optimizedQuery("gaming laptop")
            .vectorSpace("product")
            .build();

        PipelineContext context = PipelineContext.from("I need a gaming laptop", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(retrievalIntent))
                .metadata(Map.of("retrievalQueryHint", "sku-lux-41113"))
                .build())
            .build();

        step.process(context);

        ArgumentCaptor<ai.fabric.dto.RAGRequest> captor = ArgumentCaptor.forClass(ai.fabric.dto.RAGRequest.class);
        verify(ragProvider).performRag(captor.capture());
        assertThat(captor.getValue().getQuery()).isEqualTo("gaming laptop sku-lux-41113");
        assertThat(captor.getValue().getMetadata()).containsEntry("retrievalQueryHintApplied", true);
    }

    @Test
    void shouldIgnoreRetrievalQueryHintWhenMultipleRetrievalIntentsExist() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any())).thenReturn(RAGResponse.builder().success(true).documents(List.of()).context("ctx").build());

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
            new OrchestrationProperties(),
            providerOf((KnowledgeBaseOverviewService) null),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent first = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("search_one")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .optimizedQuery("laptop")
            .vectorSpace("product")
            .build();

        Intent second = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("search_two")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .optimizedQuery("tablet")
            .vectorSpace("product")
            .build();

        PipelineContext context = PipelineContext.from("compare laptop and tablet", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder()
                .intents(List.of(first, second))
                .metadata(Map.of("retrievalQueryHint", "sku-lux-41113"))
                .build())
            .build();

        step.process(context);

        ArgumentCaptor<ai.fabric.dto.RAGRequest> captor = ArgumentCaptor.forClass(ai.fabric.dto.RAGRequest.class);
        verify(ragProvider, times(2)).performRag(captor.capture());

        List<ai.fabric.dto.RAGRequest> requests = captor.getAllValues();
        assertThat(requests).hasSize(2);
        assertThat(requests.get(0).getQuery()).isEqualTo("laptop");
        assertThat(requests.get(0).getMetadata()).containsEntry("retrievalQueryHintApplied", false);
        assertThat(requests.get(1).getQuery()).isEqualTo("tablet");
        assertThat(requests.get(1).getMetadata()).containsEntry("retrievalQueryHintApplied", false);
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
