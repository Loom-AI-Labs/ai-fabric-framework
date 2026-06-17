package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.config.AIServiceConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.dto.AdvancedRAGRequest;
import ai.fabric.dto.AdvancedRAGResponse;
import ai.fabric.dto.Intent;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.policy.OrchestrationPolicy;
import ai.fabric.intent.orchestration.targets.ResolvedTarget;
import ai.fabric.intent.orchestration.targets.ResolvedTargetSource;
import ai.fabric.spi.AdvancedRAGProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AdvancedRagSupportTest {

    @Test
    void shouldGateAdvancedRagByProviderFeaturesOverridesAndIntentDecisions() {
        AdvancedRAGProvider provider = mock(AdvancedRAGProvider.class);
        AdvancedRagSupport enabled = newSupport(provider, features(true, true), null);

        assertThat(enabled.shouldUseAdvancedRag(
            null,
            true,
            "Which product policy applies to international warranty returns?",
            OrchestrationContext.forUser("user-1"),
            null
        )).isTrue();

        assertThat(newSupport(null, features(true, true), null)
            .shouldUseAdvancedRag(null, true, "What is the return policy?", OrchestrationContext.forUser("user-1"), null))
            .isFalse();

        assertThat(newSupport(provider, features(false, true), null)
            .shouldUseAdvancedRag(null, true, "What is the return policy?", OrchestrationContext.forUser("user-1"), null))
            .isFalse();

        OrchestrationContext forcedOff = OrchestrationContext.builder()
            .userId("user-1")
            .metadata(Map.of(OrchestrationContextMetadataKeys.USE_ADVANCED_RAG, false))
            .build();
        assertThat(enabled.shouldUseAdvancedRag(null, true, "What is the return policy?", forcedOff, null))
            .isFalse();

        OrchestrationContext forcedOn = OrchestrationContext.builder()
            .userId("user-1")
            .metadata(Map.of(OrchestrationContextMetadataKeys.USE_ADVANCED_RAG, true))
            .build();
        assertThat(enabled.shouldUseAdvancedRag(null, false, "short", forcedOn, null))
            .isTrue();

        PipelineContext deterministic = PipelineContext.from("query", OrchestrationContext.forUser("user-1"))
            .toBuilder()
            .orchestrationPolicy(new OrchestrationPolicy(
                null,
                null,
                null,
                OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE,
                null,
                null
            ))
            .build();
        assertThat(enabled.shouldUseAdvancedRag(null, true, "What is the return policy?", OrchestrationContext.forUser("user-1"), deterministic))
            .isFalse();

        assertThat(enabled.shouldUseAdvancedRag(
            Intent.builder().needsAdvancedRAG(false).build(),
            true,
            "What is the return policy?",
            OrchestrationContext.forUser("user-1"),
            null
        )).isFalse();

        assertThat(enabled.shouldUseAdvancedRag(
            Intent.builder().needsAdvancedRAG(true).build(),
            true,
            "simple",
            OrchestrationContext.forUser("user-1"),
            null
        )).isTrue();
    }

    @Test
    void shouldBuildAdvancedRequestWithBudgetMetadataPinnedContextAndAuth() {
        AdvancedRagSupport support = newSupport(mock(AdvancedRAGProvider.class), features(true, false), null);
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("user-1")
            .metadata(Map.of(
                "advancedRagExpansionLevel", "4",
                "advancedRagRerankingStrategy", " diversity ",
                "advancedRagContextOptimizationLevel", new StringBuilder("high")
            ))
            .build();
        PipelineContext pipelineContext = PipelineContext.from("query", context)
            .toBuilder()
            .resolvedTargets(List.of(ResolvedTarget.builder()
                .id("target-1")
                .vectorSpace("product")
                .contentText("Target content")
                .source(ResolvedTargetSource.WORKING_SET)
                .build()))
            .pinnedTargetsContext("PINNED TARGETS:\n- target one")
            .orchestrationPolicy(new OrchestrationPolicy(
                null,
                null,
                null,
                null,
                null,
                new OrchestrationPolicy.RagBudgets(null, null, null, null, null, null, List.of(), 0.77)
            ))
            .build();

        AdvancedRAGRequest request = support.buildAdvancedRagRequest(
            Intent.builder().vectorSpace("product").build(),
            context,
            "return policy",
            Map.of("source", "test"),
            pipelineContext,
            5,
            0.6
        );

        assertThat(request.getQuery()).isEqualTo("return policy");
        assertThat(request.getEntityType()).isEqualTo("product");
        assertThat(request.getMaxResults()).isEqualTo(5);
        assertThat(request.getMaxDocuments()).isEqualTo(5);
        assertThat(request.getSimilarityThreshold()).isEqualTo(0.77);
        assertThat(request.getContext()).contains("PINNED TARGETS");
        assertThat(request.getExpansionLevel()).isEqualTo(4);
        assertThat(request.getRerankingStrategy()).isEqualTo("diversity");
        assertThat(request.getContextOptimizationLevel()).isEqualTo("high");
        assertThat(request.getAuthContext().getSubjectId()).isEqualTo("user-1");
        assertThat(request.getMetadata()).containsEntry("source", "test");
        assertThatThrownBy(() -> request.getMetadata().put("other", true))
            .isInstanceOf(UnsupportedOperationException.class);

        assertThat(support.resolveSimilarityThreshold(null, 0.6)).isEqualTo(0.6);
    }

    @Test
    void shouldConvertAdvancedDocumentsAndResponse() {
        AdvancedRagSupport support = newSupport(mock(AdvancedRAGProvider.class), features(true, true), null);
        LocalDateTime timestamp = LocalDateTime.now();
        AdvancedRAGResponse.RAGDocument advancedDoc = AdvancedRAGResponse.RAGDocument.builder()
            .id("doc-1")
            .title("Return Policy")
            .content("Policy content")
            .type("policy")
            .score(0.91)
            .similarity(0.87)
            .metadata(Map.of("source", "kb"))
            .source("policy-service")
            .createdAt(timestamp)
            .author("ops")
            .tags(List.of("returns"))
            .wordCount(42)
            .language("en")
            .build();

        List<RAGResponse.RAGDocument> docs = support.convertToRagDocuments(Arrays.asList(null, advancedDoc));

        assertThat(docs).hasSize(1);
        assertThat(docs.getFirst().getId()).isEqualTo("doc-1");
        assertThat(docs.getFirst().getMetadata()).containsEntry("source", "kb");
        assertThat(docs.getFirst().getWordCount()).isEqualTo(42);
        assertThat(support.convertToRagDocuments(null)).isEmpty();

        AdvancedRAGResponse advanced = AdvancedRAGResponse.builder()
            .context("context")
            .totalDocuments(9)
            .usedDocuments(1)
            .relevanceScores(List.of(0.91))
            .confidenceScore(0.82)
            .processingTimeMs(123L)
            .requestId("req-1")
            .metadata(Map.of("provider", "advanced"))
            .timestamp(timestamp)
            .success(true)
            .build();

        RAGResponse response = support.convertToRagResponse(advanced, docs, "original query", "product");

        assertThat(response.getDocuments()).isEqualTo(docs);
        assertThat(response.getContext()).isEqualTo("context");
        assertThat(response.getTotalDocuments()).isEqualTo(9);
        assertThat(response.getUsedDocuments()).isEqualTo(1);
        assertThat(response.getConfidenceScore()).isEqualTo(0.82);
        assertThat(response.getProcessingTimeMs()).isEqualTo(123L);
        assertThat(response.getRequestId()).isEqualTo("req-1");
        assertThat(response.getOriginalQuery()).isEqualTo("original query");
        assertThat(response.getEntityType()).isEqualTo("product");
        assertThat(response.getMetadata()).containsEntry("provider", "advanced");
        assertThat(response.getTimestamp()).isEqualTo(timestamp);
        assertThat(response.getSuccess()).isTrue();
    }

    private AdvancedRagSupport newSupport(AdvancedRAGProvider provider,
                                          AIServiceConfig.FeatureFlags features,
                                          OrchestrationProperties orchestrationProperties) {
        return new AdvancedRagSupport(
            providerOf(provider),
            AIServiceConfig.builder().features(features).build(),
            orchestrationProperties
        );
    }

    private AIServiceConfig.FeatureFlags features(boolean enabled, boolean autoComplex) {
        return AIServiceConfig.FeatureFlags.builder()
            .enableAdvancedRAG(enabled)
            .autoEnableAdvancedRAGForComplexQueries(autoComplex)
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
