package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AIGenerationResponse;
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
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.dto.RAGResponse;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepAlwaysGenerateInformationTest {

    @Test
    void shouldPreserveRetrievalOnlyBehaviorByDefault() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            new AIServiceConfig(),
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

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("list")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .vectorSpace("product")
            .optimizedQuery("list products")
            .build();

        when(ragProvider.performRag(any())).thenReturn(
            RAGResponse.builder()
                .documents(List.of())
                .context("No relevant context found.")
                .success(true)
                .build()
        );

        PipelineContext context = PipelineContext.from("List products", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Search completed.");

        verify(ragProvider).performRag(any());
        verify(ragProvider, never()).performRAGQuery(any());
        verify(aiCoreService, never()).generateTextResponse(anyString(), any());
    }

    @Test
    void shouldForceGenerationForRetrievalWhenAlwaysGenerateInformationEnabled() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);

        OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
        orchestrationProperties.setAlwaysGenerateInformation(true);

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            new AIServiceConfig(),
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
            .intent("list")
            .requiresRetrieval(true)
            .requiresGeneration(false)
            .vectorSpace("product")
            .optimizedQuery("list products")
            .build();

        when(ragProvider.performRAGQuery(any())).thenReturn(
            RAGResponse.builder()
                .documents(List.of(RAGResponse.RAGDocument.builder().id("1").content("doc").build()))
                .context("doc")
                .success(true)
                .build()
        );
        when(aiCoreService.generateTextResponse(anyString(), any())).thenReturn(
            AIGenerationResponse.builder()
                .content("Generated answer")
                .model("gpt-5.4-mini")
                .processingTimeMs(320L)
                .build()
        );

        PipelineContext context = PipelineContext.from("List products", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result).isNotNull();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Generated answer");
        assertThat(result.getMetadata())
            .containsEntry("responseGenerationProviderProcessingTimeMs", 320L)
            .containsEntry("responseGenerationModel", "gpt-5.4-mini")
            .containsEntry("responseGenerationPath", "RAG_ANSWER");
        assertThat(result.getMetadata()).containsKey("responseGenerationProcessingTimeMs");
        assertThat(result.getMetadata().get("responseGenerationProcessingTimeMs")).isInstanceOf(Long.class);

        verify(ragProvider, never()).performRag(any());
        verify(ragProvider).performRAGQuery(any());
        verify(aiCoreService).generateTextResponse(anyString(), any());
    }

    @Test
    void shouldMarkRequestAttachmentsAsVisibleEvidenceInRagGenerationPrompt() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        AICoreService aiCoreService = mock(AICoreService.class);

        OrchestrationProperties orchestrationProperties = new OrchestrationProperties();
        orchestrationProperties.setAlwaysGenerateInformation(true);

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            new AIServiceConfig(),
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
            .intent("compare")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .vectorSpace("product")
            .optimizedQuery("compare laptops")
            .build();

        when(ragProvider.performRAGQuery(any())).thenReturn(
            RAGResponse.builder()
                .documents(List.of(RAGResponse.RAGDocument.builder().id("1").content("related laptop").build()))
                .context("related laptop")
                .success(true)
                .build()
        );
        when(aiCoreService.generateTextResponse(anyString(), any())).thenReturn(
            AIGenerationResponse.builder()
                .content("Generated answer")
                .model("gpt-5.4-mini")
                .build()
        );

        ResolvedTarget target = ResolvedTarget.builder()
            .id("commerce://resource/Product/1")
            .vectorSpace("product")
            .contentText("AtlasBook 14 Laptop product evidence")
            .metadata(Map.of("title", "AtlasBook 14 Laptop"))
            .source(ResolvedTargetSource.REQUEST_ATTACHMENTS)
            .build();

        PipelineContext context = PipelineContext.from("Compare the attached laptop", OrchestrationContext.forUser("user"))
            .toBuilder()
            .resolvedTargets(List.of(target))
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);

        assertThat(updated.getIntentResult().getMessage()).isEqualTo("Generated answer");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), any());
        assertThat(promptCaptor.getValue())
            .contains("ATTACHMENTS (user-provided text evidence visible to the assistant; authoritative for this turn)")
            .contains("AtlasBook 14 Laptop product evidence");
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
