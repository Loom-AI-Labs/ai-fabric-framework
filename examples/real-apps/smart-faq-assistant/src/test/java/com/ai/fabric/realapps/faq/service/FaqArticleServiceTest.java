package com.ai.fabric.realapps.faq.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.AICapabilityService;
import com.ai.fabric.realapps.faq.domain.FaqArticle;
import com.ai.fabric.realapps.faq.repo.FaqArticleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaqArticleServiceTest {

    private final FaqArticleRepository repository = mock(FaqArticleRepository.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final ObjectProvider<AICapabilityService> capabilityProvider = unavailableProvider();
    private final ObjectProvider<AICoreService> aiCoreProvider = availableProvider(aiCoreService);
    private final FaqArticleService service = new FaqArticleService(repository, capabilityProvider, aiCoreProvider);

    @Test
    void semanticSearchResolvesValidResultIdsAndSkipsMalformedRows() {
        FaqArticle password = article(1L, "Reset password");
        FaqArticle billing = article(2L, "Update billing");
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(
                Map.of("entityId", "1"),
                Map.of("id", "not-a-number"),
                Map.of(),
                Map.of("id", 2L)
            ))
            .build());
        when(repository.findById(1L)).thenReturn(Optional.of(password));
        when(repository.findById(2L)).thenReturn(Optional.of(billing));

        List<FaqArticle> results = service.semanticSearch("billing password", 5);

        assertThat(results).extracting(FaqArticle::getId).containsExactly(1L, 2L);
    }

    @Test
    void searchWithEvidenceReturnsScoresAndSafeRawResultValues() {
        FaqArticle refund = article(7L, "Refund policy");
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(Map.of(
                "entityId", "7",
                "score", "0.82",
                "source", "lucene",
                "nullable", "kept"
            )))
            .build());
        when(repository.findById(7L)).thenReturn(Optional.of(refund));

        List<FaqArticleService.SearchHit> hits = service.searchWithEvidence("refund", 3, 0.1d);

        assertThat(hits).hasSize(1);
        assertThat(hits.getFirst().article()).isSameAs(refund);
        assertThat(hits.getFirst().score()).isEqualTo(0.82d);
        assertThat(hits.getFirst().rawResult()).containsEntry("source", "lucene");
    }

    @Test
    void seedBaselineArticlesUpdatesExistingRecordsInsteadOfCreatingDuplicates() {
        FaqArticle existing = article(10L, "How do I cancel my subscription?");
        existing.setContent("old");
        when(repository.findByTitle(eq("How do I cancel my subscription?"))).thenReturn(Optional.of(existing));
        when(repository.findByTitle(eq("Do you offer refunds?"))).thenReturn(Optional.empty());
        when(repository.findByTitle(eq("How can I update my payment method?"))).thenReturn(Optional.empty());
        when(repository.findByTitle(eq("Why am I not receiving verification emails?"))).thenReturn(Optional.empty());
        when(repository.findByTitle(eq("How do I change my plan tier?"))).thenReturn(Optional.empty());
        when(repository.save(any(FaqArticle.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<FaqArticle> seeded = service.seedBaselineArticles();

        assertThat(seeded).hasSize(5);
        assertThat(existing.getContent()).contains("Cancel Subscription");
        assertThat(seeded.getFirst()).isSameAs(existing);
    }

    @Test
    void semanticSearchReturnsEmptyListWhenSearchHasNoResults() {
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .build());

        assertThat(service.semanticSearch("missing", 3)).isEmpty();
    }

    @Test
    void askUsesSearchOnlyModeWhenGenerationIsDisabled() {
        FaqArticle article = article(1L, "Refund policy");
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of(Map.of("entityId", "1")))
            .build());
        when(repository.findById(1L)).thenReturn(Optional.of(article));

        FaqArticleService.AskResult result = service.ask("Can I get a refund?", 3);

        assertThat(result.mode()).isEqualTo("SEARCH_ONLY");
        assertThat(result.answer()).contains("Top matching FAQ articles");
        assertThat(result.matches()).containsExactly(article);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> availableProvider(T value) {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> unavailableProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static FaqArticle article(long id, String title) {
        FaqArticle article = new FaqArticle();
        article.setId(id);
        article.setTitle(title);
        article.setContent(title + " content");
        return article;
    }
}
