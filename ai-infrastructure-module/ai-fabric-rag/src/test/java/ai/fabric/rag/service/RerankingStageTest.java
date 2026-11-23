package ai.fabric.rag.service;

import ai.fabric.rag.config.RAGProperties;
import ai.fabric.spi.RerankingProvider;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RerankingStageTest {
    @Test
    void reordersOriginalRecordsWithoutAcceptingProviderTextOrChangingEvidence() {
        var provider = provider();
        when(provider.rerank(any())).thenReturn(result(List.of(new RerankingProvider.RankedCandidate("candidate-1", .9),
            new RerankingProvider.RankedCandidate("candidate-0", .2))));
        var stage = new RerankingStage(provider, settings());
        var a = row("a", "approved a"); var b = row("b", "approved b");
        var result = stage.rank("question", List.of(a, b), System.nanoTime() + Duration.ofSeconds(5).toNanos());
        assertThat(result.candidates()).extracting(r -> r.get("id")).containsExactly("b", "a");
        assertThat(result.candidates().getFirst().get("content")).isEqualTo("approved b");
        assertThat(result.candidates().getFirst().get("score")).isEqualTo(.02);
        assertThat(result.diagnostics()).containsEntry("used", true).containsEntry("scoredCount", 2);
        assertThat(a.get("metadata")).isEqualTo(Map.of("owner", "trusted-owner"));
    }

    @Test
    void boundedExternalExcerptsDoNotTruncateTheOriginalCustomerEvidence() {
        var provider = provider();
        var settings = settings(); settings.setMaxCandidates(1); settings.setMaxCharactersPerCandidate(8);
        when(provider.rerank(any())).thenAnswer(call -> {
            RerankingProvider.Request request = call.getArgument(0);
            assertThat(request.candidates()).hasSize(1);
            assertThat(request.candidates().getFirst().text()).hasSize(8);
            assertThat(request.candidates().getFirst().toString()).doesNotContain("approved");
            return result(List.of(new RerankingProvider.RankedCandidate("candidate-0", .9)));
        });
        var original = row("a", "approved original full record");
        var outcome = new RerankingStage(provider, settings).rank("question", List.of(original, row("b", "another record")),
            System.nanoTime() + Duration.ofSeconds(5).toNanos());
        assertThat(outcome.candidates()).hasSize(2);
        assertThat(outcome.candidates().getFirst().get("content")).isEqualTo("approved original full record");
        assertThat(outcome.diagnostics()).containsEntry("truncatedExcerpts", 1);
    }

    @Test
    void unknownOrDuplicateReferencesCannotEnterEvidence() {
        for (var scores : List.of(List.of(new RerankingProvider.RankedCandidate("foreign", .9)),
            List.of(new RerankingProvider.RankedCandidate("candidate-0", .9), new RerankingProvider.RankedCandidate("candidate-0", .8)))) {
            var provider = provider(); when(provider.rerank(any())).thenReturn(result(scores));
            assertThatThrownBy(() -> new RerankingStage(provider, settings()).rank("q", List.of(row("a", "approved")),
                System.nanoTime() + Duration.ofSeconds(5).toNanos())).isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void missingProviderAndExpiredRequestCannotInvokeReranking() {
        assertThatThrownBy(() -> new RerankingStage(null, settings())).isInstanceOf(IllegalArgumentException.class);
        var provider = provider();
        assertThatThrownBy(() -> new RerankingStage(provider, settings()).rank("q", List.of(row("a", "approved")), 0))
            .isInstanceOf(ai.fabric.spi.RerankingUnavailableException.class).hasMessageContaining("TIMEOUT");
        verify(provider, never()).rerank(any());
    }

    @Test void transientAvailabilityFallbackIsExplicitAndRetainsOriginalOrderWithoutScores() {
        var provider = provider();
        when(provider.rerank(any())).thenThrow(new ai.fabric.spi.RerankingUnavailableException(
            ai.fabric.spi.RerankingUnavailableException.Reason.TRANSIENT_HTTP));
        var original = List.of(row("a", "approved a"), row("b", "approved b"));
        assertThatThrownBy(() -> new RerankingStage(provider, settings()).rank("q", original,
            System.nanoTime() + Duration.ofSeconds(5).toNanos())).isInstanceOf(ai.fabric.spi.RerankingUnavailableException.class);
        var settings = settings(); settings.setFailurePolicy(RAGProperties.RerankingProperties.FailurePolicy.VISIBLE_PRE_RERANK_FALLBACK);
        var result = new RerankingStage(provider, settings).rank("q", original, System.nanoTime() + Duration.ofSeconds(5).toNanos());
        assertThat(result.candidates()).isEqualTo(original);
        assertThat(result.diagnostics()).containsEntry("used", false).containsEntry("status", "PRE_RERANK_FALLBACK")
            .containsEntry("reason", "TRANSIENT_HTTP");
    }

    @Test void availabilityPolicyCannotHideAuthorizationAccountingOrInvalidReceiptFailures() {
        var settings = settings(); settings.setFailurePolicy(RAGProperties.RerankingProperties.FailurePolicy.VISIBLE_PRE_RERANK_FALLBACK);
        for (var failure : List.of(new SecurityException("authorization"), new IllegalStateException("accounting"),
            new IllegalArgumentException("invalid receipt"))) {
            var provider = provider(); when(provider.rerank(any())).thenThrow(failure);
            assertThatThrownBy(() -> new RerankingStage(provider, settings).rank("q", List.of(row("a", "approved")),
                System.nanoTime() + Duration.ofSeconds(5).toNanos())).isSameAs(failure);
        }
    }

    private static RerankingProvider provider() {
        var provider = mock(RerankingProvider.class);
        when(provider.providerId()).thenReturn("cohere"); when(provider.modelId()).thenReturn("qualified-model");
        return provider;
    }
    private static RAGProperties.RerankingProperties settings() {
        var settings = new RAGProperties.RerankingProperties();
        settings.setEnabled(true); settings.setProvider("cohere"); settings.setModel("qualified-model"); return settings;
    }
    private static Map<String, Object> row(String id, String text) {
        return Map.of("id", id, "content", text, "metadata", Map.of("owner", "trusted-owner"), "score", .02);
    }
    private static RerankingProvider.Result result(List<RerankingProvider.RankedCandidate> scores) {
        return new RerankingProvider.Result(scores, new RerankingProvider.Usage(Map.of("search_units", 1L)), "native-id");
    }
}
