package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PostActionGenerationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.RelationshipQueryPostActionGenerationProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AIGenerationRequest;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.IntentType;
import ai.fabric.dto.MultiIntentResponse;
import ai.fabric.dto.ResponseGenerationProfile;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.KnowledgeBaseOverviewService;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.policy.OrchestrationProfile;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepConciseGenerationTest {

    @Test
    void shouldUseConciseGenerationPathForSimpleSingleSpaceInformationQuery() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(
                    doc("SKU-ALI-52056", 0.95d, "Alienware m18 R2 is a high-performance gaming laptop."),
                    doc("SKU-ALI-52057", 0.90d, "It features strong graphics performance and a large display.")
                ))
                .success(true)
                .build()
        );

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Alienware m18 R2 is a powerful gaming laptop with strong graphics performance.")
                .model("gpt-5.4-mini")
                .processingTimeMs(120L)
                .build()
        );

        IntentHandlingStep step = newStep(ragProvider, aiCoreService);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("product_summary")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .responseProfile(ResponseGenerationProfile.CONCISE)
            .vectorSpace("product")
            .build();

        PipelineContext context = PipelineContext.from("Tell me about Alienware m18 R2", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).contains("Alienware m18 R2");
        assertThat(result.getMetadata())
            .containsEntry("responseGenerationPath", "RAG_ANSWER_CONCISE")
            .containsEntry("responseGenerationProviderProcessingTimeMs", 120L)
            .containsEntry("responseGenerationModel", "gpt-5.4-mini");

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(requestCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requestCaptor.getValue().getMaxTokens()).isEqualTo(400);
        verify(aiCoreService, never()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldKeepStandardGenerationPathWhenExtractorRequestsStandardProfile() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(
                    doc("SKU-RAZ-36052", 0.95d, "Razer Blade 16 targets high-end gaming workloads."),
                    doc("SKU-ALI-52056", 0.92d, "Alienware m18 R2 offers strong gaming performance.")
                ))
                .success(true)
                .build()
        );

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Detailed analysis answer")
                .model("gpt-5.4-mini")
                .processingTimeMs(210L)
                .build()
        );

        IntentHandlingStep step = newStep(ragProvider, aiCoreService);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("catalog_analysis")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .responseProfile(ResponseGenerationProfile.STANDARD)
            .vectorSpace("product")
            .build();

        PipelineContext context = PipelineContext.from(
                "Analyze and summarize high performance laptops for gaming",
                OrchestrationContext.forUser("user")
            )
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMetadata())
            .containsEntry("responseGenerationPath", "RAG_ANSWER")
            .containsEntry("responseGenerationProviderProcessingTimeMs", 210L)
            .containsEntry("responseGenerationModel", "gpt-5.4-mini");

        verify(aiCoreService).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
        verify(aiCoreService, never()).generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldUseConfiguredDeepBudgetWhenExtractorRequestsDeepProfile() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(
                    doc("SKU-RAZ-36052", 0.95d, "Razer Blade 16 targets high-end gaming workloads."),
                    doc("SKU-ALI-52056", 0.92d, "Alienware m18 R2 offers strong gaming performance.")
                ))
                .success(true)
                .build()
        );

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Deep comparison answer")
                .model("gpt-5.4-mini")
                .processingTimeMs(240L)
                .build()
        );

        IntentHandlingStep step = newStep(ragProvider, aiCoreService);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("catalog_analysis")
            .requiresRetrieval(true)
            .requiresGeneration(true)
            .responseProfile(ResponseGenerationProfile.DEEP)
            .vectorSpace("product")
            .build();

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            null,
            null,
            null,
            null,
            null,
            new OrchestrationPolicy.ResponseGenerationBudgets(null, null, 1_400)
        );

        PipelineContext context = PipelineContext.from(
                "Analyze and summarize high performance laptops for gaming",
                OrchestrationContext.forUser("user")
            )
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .orchestrationPolicy(policy)
            .build();

        OrchestrationResult result = step.process(context).getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMetadata())
            .containsEntry("responseGenerationPath", "RAG_ANSWER_DEEP")
            .containsEntry("responseGenerationProviderProcessingTimeMs", 240L)
            .containsEntry("responseGenerationModel", "gpt-5.4-mini");

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(requestCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requestCaptor.getValue().getMaxTokens()).isEqualTo(1_400);
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

    private static RAGResponse.RAGDocument doc(String id, double score, String content) {
        return RAGResponse.RAGDocument.builder()
            .id(id)
            .score(score)
            .content(content)
            .build();
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
