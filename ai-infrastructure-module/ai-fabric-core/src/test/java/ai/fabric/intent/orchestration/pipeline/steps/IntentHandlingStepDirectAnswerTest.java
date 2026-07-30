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
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.request.ConversationPersistencePolicy;
import ai.fabric.intent.orchestration.request.OrchestrationIntentPolicy;
import ai.fabric.intent.orchestration.request.OrchestrationRequest;
import ai.fabric.intent.orchestration.request.OrchestrationRequestPurpose;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentHandlingStepDirectAnswerTest {

    @Test
    void structuredOutputOnlyPolicySkipsOrdinaryIntentHandlingAndGeneration() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        IntentHandlingStep step = step(
            actionRegistry,
            ragProvider,
            aiCoreService
        );

        Intent actionLikeIntent = Intent.builder()
            .type(IntentType.ACTION)
            .intent("request_refund")
            .action("request_refund")
            .build();
        OrchestrationRequest request = new OrchestrationRequest(
            "Would a $75 refund be approved?",
            OrchestrationContext.forUser("user"),
            null,
            ConversationPersistencePolicy.NEVER,
            null,
            null,
            null,
            OrchestrationRequestPurpose.SPECIALIST,
            OrchestrationIntentPolicy.STRUCTURED_OUTPUT_ONLY
        );
        PipelineContext context = PipelineContext.from(request)
            .toBuilder()
            .intentResponse(
                MultiIntentResponse.builder()
                    .intents(List.of(actionLikeIntent))
                    .build()
            )
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType())
            .isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData())
            .containsEntry("structuredOutputOnly", true);
        assertThat(result.getMetadata())
            .containsEntry("intentPolicy", "STRUCTURED_OUTPUT_ONLY")
            .containsEntry("ordinaryIntentHandlingSkipped", true);
        verifyNoInteractions(actionRegistry);
        verify(ragProvider, never()).performRag(any());
        verify(ragProvider, never()).performRAGQuery(any());
        verify(aiCoreService, never()).generateTextResponse(anyString(), any());
    }

    @Test
    void shouldReturnDirectAnswerWithoutCallingRagOrGeneration() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);

        IntentHandlingStep step = step(
            mock(AIActionRegistry.class),
            ragProvider,
            aiCoreService
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("ack")
            .requiresRetrieval(false)
            .directAnswer("You're welcome.")
            .build();

        PipelineContext context = PipelineContext.from("Thanks!", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("You're welcome.");

        verify(ragProvider, never()).performRag(any());
        verify(ragProvider, never()).performRAGQuery(any());
        verify(aiCoreService, never()).generateTextResponse(anyString(), any());
    }

    private IntentHandlingStep step(
        AIActionRegistry actionRegistry,
        RAGProvider ragProvider,
        AICoreService aiCoreService
    ) {
        return new IntentHandlingStep(
            actionRegistry,
            providerOf(ragProvider),
            aiCoreService,
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
