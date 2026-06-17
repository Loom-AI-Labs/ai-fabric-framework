package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.core.LlmPurpose;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.AIGenerationResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.information.ReadActionResolutionService;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
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

class InformationAdvancedRagExecutionSupportTest {

    @Test
    void shouldReturnNullWhenAdvancedProviderIsUnavailable() {
        InformationAdvancedRagExecutionSupport support = newSupport(null, mock(AICoreService.class));

        OrchestrationResult result = support.execute(
            Intent.builder().vectorSpace("product").build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("query", OrchestrationContext.forUser("user-1")),
            true,
            "query",
            "query",
            Map.of(),
            null
        );

        assertThat(result).isNull();
    }

    @Test
    void shouldUseAdvancedProviderResponseWhenGrounded() {
        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        when(provider.performAdvancedRAG(any())).thenReturn(
            AdvancedRAGResponse.builder()
                .success(true)
                .response("Advanced answer")
                .context("Policy context")
                .expandedQueries(List.of("returns", "refunds"))
                .confidenceScore(0.82d)
                .rerankingStrategy("diversity")
                .contextOptimizationLevel("high")
                .documents(List.of(advancedDoc("doc-1", "Policy context")))
                .build()
        );
        InformationAdvancedRagExecutionSupport support = newSupport(provider, mock(AICoreService.class));

        OrchestrationResult result = support.execute(
            Intent.builder().vectorSpace("product").requiresGeneration(true).build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("return policy", OrchestrationContext.forUser("user-1")),
            true,
            "return policy",
            "return policy",
            Map.of("source", "test"),
            null
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Advanced answer");
        assertThat(result.getData().get("answer")).isEqualTo("Advanced answer");
        assertThat(result.getData().get("expandedQueries")).isEqualTo(List.of("returns", "refunds"));
        assertThat(result.getData().get("confidenceScore")).isEqualTo(0.82d);
        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> docs = (List<RAGResponse.RAGDocument>) result.getData().get("documents");
        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().getId()).isEqualTo("doc-1");

        ArgumentCaptor<AdvancedRAGRequest> requestCaptor = ArgumentCaptor.forClass(AdvancedRAGRequest.class);
        verify(provider).performAdvancedRAG(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getQuery()).isEqualTo("return policy");
        assertThat(requestCaptor.getValue().getEntityType()).isEqualTo("product");
        assertThat(requestCaptor.getValue().getMaxResults()).isEqualTo(5);
        assertThat(requestCaptor.getValue().getSimilarityThreshold()).isEqualTo(0.6d);
    }

    @Test
    void shouldRegenerateWhenReadActionEvidenceIsPresent() {
        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        when(provider.performAdvancedRAG(any())).thenReturn(
            AdvancedRAGResponse.builder()
                .success(true)
                .response("Context-only answer")
                .context("Alpha Record is a candidate.")
                .confidenceScore(0.9d)
                .documents(List.of(advancedDoc("record-1", "Alpha Record is a candidate.")))
                .build()
        );
        AICoreService aiCoreService = mock(AICoreService.class);
        when(aiCoreService.generateTextResponse(anyString(), eq(LlmPurpose.GENERATION))).thenReturn(
            AIGenerationResponse.builder()
                .content("Alpha Record has score 7.5 and status ready.")
                .build()
        );
        ReadActionResolutionService.ResolutionOutcome resolutionOutcome =
            ReadActionResolutionService.ResolutionOutcome.continueWithRag(
                "READ ACTION EVIDENCE\n- evidence: {\"score\":7.5,\"status\":\"ready\"}",
                List.of("records"),
                List.of(),
                Map.of("attempted", true, "useRag", true)
            );
        InformationAdvancedRagExecutionSupport support = newSupport(provider, aiCoreService);

        OrchestrationResult result = support.execute(
            Intent.builder().vectorSpace("records").requiresGeneration(true).build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("Alpha Record score status", OrchestrationContext.forUser("user-1")),
            true,
            "Alpha Record score status",
            "Alpha Record score status",
            Map.of(),
            resolutionOutcome
        );

        assertThat(result.getMessage()).isEqualTo("Alpha Record has score 7.5 and status ready.");
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(aiCoreService).generateTextResponse(promptCaptor.capture(), eq(LlmPurpose.GENERATION));
        assertThat(promptCaptor.getValue())
            .contains("READ ACTION EVIDENCE POLICY")
            .contains("\"score\":7.5")
            .contains("Alpha Record is a candidate.");
    }

    private InformationAdvancedRagExecutionSupport newSupport(AdvancedRAGProvider provider,
                                                              AICoreService aiCoreService) {
        AIServiceConfig config = new AIServiceConfig();
        return new InformationAdvancedRagExecutionSupport(
            new AdvancedRagSupport(providerOf(provider), config, new OrchestrationProperties()),
            new RagResponseGenerationSupport(
                aiCoreService,
                config,
                new PromptTemplateResolver(
                    new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
                    new PromptBundleProperties()
                ),
                new PromptRenderer()
            )
        );
    }

    private static AdvancedRAGResponse.RAGDocument advancedDoc(String id, String content) {
        return AdvancedRAGResponse.RAGDocument.builder()
            .id(id)
            .title(id)
            .content(content)
            .type("record")
            .build();
    }

    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
