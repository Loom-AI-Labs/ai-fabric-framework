package com.ai.infrastructure.intent.orchestration.pipeline.steps;

import com.ai.infrastructure.config.AIServiceConfig;
import com.ai.infrastructure.config.OrchestrationProperties;
import com.ai.infrastructure.config.PostActionGenerationProperties;
import com.ai.infrastructure.config.RelationshipQueryPostActionGenerationProperties;
import com.ai.infrastructure.config.VectorSpaceRoutingProperties;
import com.ai.infrastructure.config.PromptBundleProperties;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntentHandlingStepFanOutTest {

    @Test
    void shouldMergeFanOutDocumentsByRankAndTagVectorSpace() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenAnswer(invocation -> {
            RAGRequest request = invocation.getArgument(0);
            if ("faq".equals(request.getEntityType())) {
                return RAGResponse.builder()
                    .documents(List.of(
                        doc("faq-1", 0.9d, "FAQ one"),
                        doc("faq-2", 0.2d, "FAQ two")
                    ))
                    .success(true)
                    .build();
            }
            if ("policies".equals(request.getEntityType())) {
                return RAGResponse.builder()
                    .documents(List.of(
                        doc("pol-1", 0.8d, "Policy one"),
                        doc("pol-2", 0.1d, "Policy two")
                    ))
                    .success(true)
                    .build();
            }
            return RAGResponse.builder().documents(List.of()).success(true).build();
        });

        IntentHandlingStep step = newStep(ragProvider, mock(AICoreService.class));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace("faq,policies")
            .requiresGeneration(false)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> docs = (List<RAGResponse.RAGDocument>) result.getData().get("documents");
        assertThat(docs).extracting(RAGResponse.RAGDocument::getId).containsExactly("faq-1", "pol-1", "faq-2", "pol-2");
        assertThat(docs.get(0).getMetadata()).containsEntry("vectorSpace", "faq");
        assertThat(docs.get(1).getMetadata()).containsEntry("vectorSpace", "policies");

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider, org.mockito.Mockito.times(2)).performRag(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).hasSize(2);
        assertThat(requestCaptor.getAllValues())
            .extracting(RAGRequest::getThreshold)
            .containsOnly(0.25d);
    }

    @Test
    void shouldReturnClarificationWhenFanOutIsWeak() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(doc("d1", 0.1d, "low score")))
                .success(true)
                .build()
        );

        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setClarificationThreshold(0.4d);
        routingProperties.setFanOutTopKPerSpace(1);

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            mock(AICoreService.class),
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            routingProperties,
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
            .intent("refund_policy")
            .vectorSpace("faq,policies")
            .requiresGeneration(false)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).containsEntry("candidateVectorSpaces", List.of("faq", "policies"));
    }

    @Test
    void shouldLetGenerationHandleWeakFanOutEvidenceWhenGenerationRequested() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(doc("d1", 0.1d, "low score")))
                .success(true)
                .build()
        );

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Generated safe answer")
                .build()
        );

        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setClarificationThreshold(0.4d);
        routingProperties.setFanOutTopKPerSpace(1);

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            routingProperties,
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
            .intent("refund_policy")
            .vectorSpace("faq,policies")
            .requiresGeneration(true)
            .build();

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.getMessage()).isEqualTo("Generated safe answer");
        assertThat(result.getData()).containsEntry("vectorSpaceRoutingStrategy", "FAN_OUT");
        assertThat(result.getData()).containsEntry("bestScore", 0.1d);
        verify(aiCoreService).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
    }

    @Test
    void shouldUseGenerationPurposeWhenGeneratingFanOutAnswer() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(doc("faq-1", 0.9d, "FAQ one"), doc("pol-1", 0.8d, "Policy one")))
                .success(true)
                .build()
        );

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateContent(any(AIGenerationRequest.class), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Answer")
                .model("gpt-5.4-mini")
                .processingTimeMs(210L)
                .build()
        );

        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setClarificationThreshold(0.0d);
        routingProperties.setFanOutTopKPerSpace(2);

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            routingProperties,
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
            .intent("refund_policy")
            .vectorSpace("faq,policies")
            .requiresGeneration(true)
            .responseProfile(ResponseGenerationProfile.CONCISE)
            .build();

        PipelineContext context = PipelineContext.from("What is the refund policy?", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        PipelineContext updated = step.process(context);
        OrchestrationResult result = updated.getIntentResult();

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Answer");
        assertThat(result.getMetadata())
            .containsEntry("responseGenerationProviderProcessingTimeMs", 210L)
            .containsEntry("responseGenerationModel", "gpt-5.4-mini")
            .containsEntry("responseGenerationPath", "RAG_ANSWER_CONCISE");

        ArgumentCaptor<AIGenerationRequest> requestCaptor = ArgumentCaptor.forClass(AIGenerationRequest.class);
        verify(aiCoreService).generateContent(requestCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(requestCaptor.getValue().getMaxTokens()).isEqualTo(400);
        verify(aiCoreService, never()).generateTextResponse(anyString(), eq(LlmPurpose.GENERATION));
        verify(aiCoreService, never()).generateTextResponse(anyString(), eq(LlmPurpose.ORCHESTRATION));
    }

    @Test
    void shouldBoundDefaultGenerationContextWhenPolicyDoesNotSetBudgets() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(
                    doc("doc-1", 0.95d, "A".repeat(900)),
                    doc("doc-2", 0.90d, "B".repeat(900)),
                    doc("doc-3", 0.85d, "C".repeat(900)),
                    doc("doc-4", 0.80d, "D".repeat(900)),
                    doc("doc-5", 0.75d, "E".repeat(900)),
                    doc("doc-6", 0.70d, "F".repeat(900))
                ))
                .success(true)
                .build()
        );

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Answer")
                .build()
        );

        IntentHandlingStep step = newStep(ragProvider, aiCoreService);

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("catalog_summary")
            .vectorSpace("product")
            .requiresGeneration(true)
            .build();

        PipelineContext context = PipelineContext.from("summarize the catalog", OrchestrationContext.forUser("user"))
            .toBuilder()
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        step.process(context);

        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        String prompt = promptCaptor.getValue();
        assertThat(prompt).contains("doc-1", "doc-2", "doc-3", "doc-4");
        assertThat(prompt).doesNotContain("doc-5", "doc-6");
    }

    @Test
    void shouldUsePolicySimilarityThresholdForSingleSpaceRetrieval() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder().documents(List.of(doc("p1", 0.11d, "Product one"))).success(true).build()
        );

        IntentHandlingStep step = newStep(ragProvider, mock(AICoreService.class));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("catalog_summary")
            .vectorSpace("product")
            .requiresGeneration(false)
            .build();

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "navigator",
            null,
            OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.RagBudgets(null, null, null, null, null, null, List.of(), 0.1d)
        );

        PipelineContext context = PipelineContext.from("summarize gaming laptops", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        step.process(context);

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRAGQuery(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getThreshold()).isEqualTo(0.1d);
    }

    @Test
    void shouldNotFanOutWhenFanoutDisabledByPolicy() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder().documents(List.of()).success(true).build()
        );

        IntentHandlingStep step = newStep(ragProvider, mock(AICoreService.class));

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("refund_policy")
            .vectorSpace("faq,policies")
            .requiresGeneration(false)
            .build();

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "navigator",
            null,
            OrchestrationProperties.InformationMode.LLM_DRIVEN,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.RagBudgets(false, null, null, null, null, null, List.of())
        );

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        step.process(context);

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider, times(1)).performRag(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getEntityType()).isEqualTo("faq");
    }

    @Test
    void shouldUseMaxSpacesBudgetForDeterministicFallbackVectorSpaces() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRAGQuery(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder().documents(List.of()).context("").success(true).build()
        );

        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Answer")
                .processingTimeMs(175L)
                .build()
        );

        KnowledgeBaseOverviewService overviewService = mock(KnowledgeBaseOverviewService.class);
        when(overviewService.getOverview()).thenReturn(com.ai.infrastructure.intent.KnowledgeBaseOverview.builder()
            .entityTypes(List.of("a", "b", "c", "d", "e"))
            .documentsByType(Map.of("a", 10L, "b", 9L, "c", 8L, "d", 7L, "e", 6L))
            .build());

        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setFanOutMaxSpaces(2);
        routingProperties.setFanOutTopKPerSpace(1);
        routingProperties.setClarificationThreshold(0.0d);

        OrchestrationProperties orchestrationProperties = new OrchestrationProperties();

        IntentHandlingStep step = new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            routingProperties,
            new RankBasedMerger(),
            new RelationshipQueryPostActionGenerationProperties(),
            new PostActionGenerationProperties(),
            providerOf(new ObjectMapper()),
            orchestrationProperties,
            providerOf(overviewService),
            null,
            new InMemoryPendingActionStore(),
            new InMemoryActionDraftStore(),
            promptTemplateResolver(),
            new PromptRenderer()
        );

        Intent intent = Intent.builder()
            .type(IntentType.INFORMATION)
            .intent("search")
            .requiresRetrieval(true)
            .vectorSpace(null)
            .build();

        OrchestrationPolicy policy = new OrchestrationPolicy(
            OrchestrationProfile.DEFAULT,
            "navigator_deep",
            null,
            OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE,
            OrchestrationPolicy.OrchestrationCapabilities.defaults(),
            new OrchestrationPolicy.RagBudgets(true, 4, 1, null, null, null, List.of())
        );

        PipelineContext context = PipelineContext.from("q", OrchestrationContext.forUser("user"))
            .toBuilder()
            .orchestrationPolicy(policy)
            .intentResponse(MultiIntentResponse.builder().intents(List.of(intent)).build())
            .build();

        step.process(context);

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider, times(4)).performRAGQuery(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues())
            .extracting(RAGRequest::getEntityType)
            .containsExactly("a", "b", "c", "d");
    }

    private IntentHandlingStep newStep(RAGProvider ragProvider, AICoreService aiCoreService) {
        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setClarificationThreshold(0.0d);
        routingProperties.setFanOutTopKPerSpace(2);
        routingProperties.setFanOutRagThreshold(0.25d);
        return new IntentHandlingStep(
            mock(AIActionRegistry.class),
            providerOf(ragProvider),
            aiCoreService,
            mock(AIServiceConfig.class),
            providerOf((AdvancedRAGProvider) null),
            routingProperties,
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

    private RAGResponse.RAGDocument doc(String id, double score, String content) {
        return RAGResponse.RAGDocument.builder()
            .id(id)
            .score(score)
            .content(content)
            .metadata(Map.of())
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
