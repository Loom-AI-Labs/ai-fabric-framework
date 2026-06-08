package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.LlmPurpose;
import com.ai.infrastructure.dto.AIGenerationRequest;
import com.ai.infrastructure.dto.AIGenerationResponse;
import com.ai.infrastructure.dto.Intent;
import com.ai.infrastructure.dto.IntentType;
import com.ai.infrastructure.dto.MultiIntentResponse;
import com.ai.infrastructure.dto.ResponseGenerationProfile;
import com.ai.infrastructure.dto.RAGRequest;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.intent.KnowledgeBaseOverviewService;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationPolicy;
import com.ai.infrastructure.intent.orchestration.policy.OrchestrationProfile;
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
