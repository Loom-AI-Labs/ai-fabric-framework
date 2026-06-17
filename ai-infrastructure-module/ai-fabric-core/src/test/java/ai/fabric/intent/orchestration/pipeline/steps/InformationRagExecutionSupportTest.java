package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.config.VectorSpaceRoutingProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.Intent;
import ai.fabric.dto.RAGRequest;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.vectorspace.RankBasedMerger;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.spi.AdvancedRAGProvider;
import ai.fabric.spi.RAGProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InformationRagExecutionSupportTest {

    @Test
    void shouldReturnNoContextResultWhenRagProviderIsUnavailable() {
        InformationRagExecutionSupport support = newSupport(null, null);

        OrchestrationResult result = support.basic(
            Intent.builder().vectorSpace("product").build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("query", OrchestrationContext.forUser("user-1")),
            false,
            "query",
            "query",
            new LinkedHashMap<>(),
            null,
            null
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isEqualTo("No relevant context found.");
        assertThat(result.getData().get("details")).isEqualTo("RAG module is not enabled (no RAGProvider bean present).");
        assertThat(result.getData().get("documents")).isEqualTo(List.of());
    }

    @Test
    void shouldExecuteBasicRetrievalWithExpectedRequestShape() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(doc("doc-1", 0.9d, "Policy")))
                .success(true)
                .build()
        );
        InformationRagExecutionSupport support = newSupport(ragProvider, null);

        OrchestrationResult result = support.basic(
            Intent.builder().vectorSpace("product").build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("return policy", OrchestrationContext.forUser("user-1")),
            false,
            "return policy",
            "return policy",
            new LinkedHashMap<>(Map.of("source", "test")),
            null,
            null
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMessage()).isEqualTo("Search completed.");
        assertThat(result.getData().get("documents")).isEqualTo(List.of(doc("doc-1", 0.9d, "Policy")));

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider).performRag(requestCaptor.capture());
        RAGRequest request = requestCaptor.getValue();
        assertThat(request.getQuery()).isEqualTo("return policy");
        assertThat(request.getEntityType()).isEqualTo("product");
        assertThat(request.getLimit()).isEqualTo(5);
        assertThat(request.getThreshold()).isEqualTo(0.6d);
        assertThat(request.getMetadata()).containsEntry("source", "test");
        assertThat(request.getAuthContext().getSubjectId()).isEqualTo("user-1");
    }

    @Test
    void shouldMergeFanOutDocumentsByRankAndTagVectorSpace() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenAnswer(invocation -> {
            RAGRequest request = invocation.getArgument(0);
            if ("faq".equals(request.getEntityType())) {
                return RAGResponse.builder()
                    .documents(List.of(doc("faq-1", 0.9d, "FAQ one"), doc("faq-2", 0.2d, "FAQ two")))
                    .success(true)
                    .build();
            }
            return RAGResponse.builder()
                .documents(List.of(doc("pol-1", 0.8d, "Policy one"), doc("pol-2", 0.1d, "Policy two")))
                .success(true)
                .build();
        });
        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setFanOutTopKPerSpace(2);
        routingProperties.setFanOutRagThreshold(0.25d);
        InformationRagExecutionSupport support = newSupport(ragProvider, routingProperties);

        OrchestrationResult result = support.fanOut(
            Intent.builder().vectorSpace("faq,policies").build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("refund", OrchestrationContext.forUser("user-1")),
            true,
            false,
            "refund",
            "refund",
            new LinkedHashMap<>(),
            List.of("faq", "policies"),
            null,
            null
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.INFORMATION_PROVIDED);
        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) result.getData().get("documents");
        assertThat(documents).extracting(RAGResponse.RAGDocument::getId)
            .containsExactly("faq-1", "pol-1", "faq-2", "pol-2");
        assertThat(documents.get(0).getMetadata()).containsEntry("vectorSpace", "faq");
        assertThat(documents.get(1).getMetadata()).containsEntry("vectorSpace", "policies");
        assertThat(result.getData().get("candidateVectorSpaces")).isEqualTo(List.of("faq", "policies"));
        assertThat(result.getData().get("vectorSpaceRoutingStrategy")).isEqualTo("FAN_OUT");

        ArgumentCaptor<RAGRequest> requestCaptor = ArgumentCaptor.forClass(RAGRequest.class);
        verify(ragProvider, org.mockito.Mockito.times(2)).performRag(requestCaptor.capture());
        assertThat(requestCaptor.getAllValues()).extracting(RAGRequest::getThreshold).containsOnly(0.25d);
    }

    @Test
    void shouldReturnClarificationWhenFanOutEvidenceIsWeakAndNotDeterministic() {
        RAGProvider ragProvider = mock(RAGProvider.class);
        when(ragProvider.performRag(any(RAGRequest.class))).thenReturn(
            RAGResponse.builder()
                .documents(List.of(doc("low", 0.1d, "weak evidence")))
                .success(true)
                .build()
        );
        VectorSpaceRoutingProperties routingProperties = new VectorSpaceRoutingProperties();
        routingProperties.setFanOutTopKPerSpace(1);
        routingProperties.setClarificationThreshold(0.4d);
        InformationRagExecutionSupport support = newSupport(ragProvider, routingProperties);

        OrchestrationResult result = support.fanOut(
            Intent.builder().vectorSpace("faq,policies").build(),
            OrchestrationContext.forUser("user-1"),
            PipelineContext.from("refund", OrchestrationContext.forUser("user-1")),
            false,
            false,
            "refund",
            "refund",
            new LinkedHashMap<>(),
            List.of("faq", "policies"),
            null,
            null
        );

        assertThat(result.getType()).isEqualTo(OrchestrationResultType.CLARIFICATION_REQUIRED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("Please specify one of: faq, policies");
        assertThat(result.getData().get("bestScore")).isEqualTo(0.1d);
        assertThat(result.getData().get("candidateVectorSpaces")).isEqualTo(List.of("faq", "policies"));
    }

    private InformationRagExecutionSupport newSupport(RAGProvider ragProvider,
                                                      VectorSpaceRoutingProperties routingProperties) {
        AIServiceConfig config = new AIServiceConfig();
        return new InformationRagExecutionSupport(
            providerOf(ragProvider),
            new AdvancedRagSupport(providerOf((AdvancedRAGProvider) null), config, new OrchestrationProperties()),
            routingProperties,
            new RankBasedMerger(),
            new RagResponseGenerationSupport(
                mock(AICoreService.class),
                config,
                new PromptTemplateResolver(
                    new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
                    new PromptBundleProperties()
                ),
                new PromptRenderer()
            )
        );
    }

    private static RAGResponse.RAGDocument doc(String id, double score, String content) {
        return RAGResponse.RAGDocument.builder()
            .id(id)
            .score(score)
            .content(content)
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
