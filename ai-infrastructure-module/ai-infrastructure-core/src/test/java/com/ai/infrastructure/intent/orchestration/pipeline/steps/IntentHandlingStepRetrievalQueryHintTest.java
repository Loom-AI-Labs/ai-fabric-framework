package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.vectorspace.RankBasedMerger;
import com.ai.infrastructure.prompt.ClasspathPromptTemplateStore;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.spi.AdvancedRAGProvider;
import com.ai.infrastructure.spi.RAGProvider;
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

        ArgumentCaptor<com.ai.infrastructure.dto.RAGRequest> captor = ArgumentCaptor.forClass(com.ai.infrastructure.dto.RAGRequest.class);
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

        ArgumentCaptor<com.ai.infrastructure.dto.RAGRequest> captor = ArgumentCaptor.forClass(com.ai.infrastructure.dto.RAGRequest.class);
        verify(ragProvider, times(2)).performRag(captor.capture());

        List<com.ai.infrastructure.dto.RAGRequest> requests = captor.getAllValues();
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
