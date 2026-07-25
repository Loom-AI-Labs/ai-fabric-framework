package com.ai.fabric.realapps.cloudvector.service;

import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import com.ai.fabric.realapps.cloudvector.domain.KnowledgeBaseArticle;
import com.ai.fabric.realapps.cloudvector.repo.KnowledgeBaseArticleRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KnowledgeBaseArticleServiceTest {

    private final KnowledgeBaseArticleRepository repository = mock(KnowledgeBaseArticleRepository.class);
    private final AICoreService aiCoreService = mock(AICoreService.class);
    private final ObjectProvider<AIEntityIndexingGateway> indexingProvider =
        unavailableProvider();
    private final ObjectProvider<AICoreService> aiCoreProvider = availableProvider(aiCoreService);
    private final KnowledgeBaseArticleService service = new KnowledgeBaseArticleService(
        repository,
        indexingProvider,
        aiCoreProvider
    );

    @Test
    void semanticSearchBuildsHitsAndSkipsMalformedRows() {
        KnowledgeBaseArticle article = article(7L, "Qdrant setup", "search", "x".repeat(240));
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .maxScore(0.98d)
            .results(List.of(
                Map.of("entityId", "7", "score", 0.91d),
                Map.of("entityId", "bad"),
                Map.of()
            ))
            .build());
        when(repository.findById(7L)).thenReturn(Optional.of(article));

        List<KnowledgeBaseArticleService.SearchHit> hits = service.semanticSearch("qdrant", 5, 0.2d);

        assertThat(hits).hasSize(1);
        KnowledgeBaseArticleService.SearchHit hit = hits.getFirst();
        assertThat(hit.id()).isEqualTo(7L);
        assertThat(hit.title()).isEqualTo("Qdrant setup");
        assertThat(hit.category()).isEqualTo("search");
        assertThat(hit.score()).isEqualTo(0.91d);
        assertThat(hit.maxScore()).isEqualTo(0.98d);
        assertThat(hit.snippet()).hasSize(223).endsWith("...");
    }

    @Test
    void semanticSearchReturnsEmptyListWhenSearchHasNoResults() {
        when(aiCoreService.performSearch(any(AISearchRequest.class))).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .build());

        assertThat(service.semanticSearch("missing", 3, 0.2d)).isEmpty();
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

    private static KnowledgeBaseArticle article(long id, String title, String category, String content) {
        KnowledgeBaseArticle article = new KnowledgeBaseArticle();
        article.setId(id);
        article.setTitle(title);
        article.setCategory(category);
        article.setContent(content);
        return article;
    }
}
