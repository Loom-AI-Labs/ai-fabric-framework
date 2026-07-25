package com.ai.fabric.realapps.faq.service;

import com.ai.fabric.realapps.faq.domain.FaqArticle;
import com.ai.fabric.realapps.faq.repo.FaqArticleRepository;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqArticleService {

    private static final String ENTITY_TYPE = "faq-article";

    private final FaqArticleRepository repository;
    private final ObjectProvider<AIEntityIndexingGateway> indexingGatewayProvider;
    private final ObjectProvider<AICoreService> aiCoreServiceProvider;

    @Value("${ai.service.features.enable-generation:false}")
    private boolean generationEnabled;

    @Transactional
    public FaqArticle create(String title, String content, String category, List<String> tags) {
        FaqArticle article = new FaqArticle();
        article.setTitle(title);
        article.setContent(content);
        article.setCategory(category);
        article.setTags(tags == null ? null : String.join(",", tags));
        article.setCreatedAt(Instant.now());
        article.setUpdatedAt(Instant.now());

        FaqArticle saved = repository.save(article);
        index(saved, AIProcessOperation.CREATE);
        return saved;
    }

    @Transactional
    public FaqArticle update(long id, String title, String content, String category, List<String> tags) {
        FaqArticle article = repository.findById(id).orElseThrow();
        if (title != null) {
            article.setTitle(title);
        }
        if (content != null) {
            article.setContent(content);
        }
        if (category != null) {
            article.setCategory(category);
        }
        if (tags != null) {
            article.setTags(String.join(",", tags));
        }
        article.setUpdatedAt(Instant.now());
        FaqArticle saved = repository.save(article);
        index(saved, AIProcessOperation.UPDATE);
        return saved;
    }

    public List<FaqArticle> list() {
        return repository.findAll();
    }

    public FaqArticle get(long id) {
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public List<FaqArticle> seedBaselineArticles() {
        return FaqDemoCatalog.baselineArticles().stream()
            .map(this::upsertSeedArticle)
            .toList();
    }

    @Transactional
    public int reindexAll() {
        List<FaqArticle> articles = repository.findAll();
        for (FaqArticle article : articles) {
            index(article, AIProcessOperation.UPDATE);
        }
        return articles.size();
    }

    public List<FaqArticle> semanticSearch(String query, int limit) {
        return semanticSearch(query, limit, 0.3d);
    }

    public List<FaqArticle> semanticSearch(String query, int limit, double threshold) {
        return searchWithEvidence(query, limit, threshold).stream()
            .map(SearchHit::article)
            .toList();
    }

    public List<SearchHit> searchWithEvidence(String query, int limit, double threshold) {
        AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
        if (aiCoreService == null) {
            throw new IllegalStateException("AICoreService not available (ensure AI Fabric dependencies are present)");
        }

        AISearchResponse response = aiCoreService.performSearch(AISearchRequest.builder()
            .query(query)
            .entityType(ENTITY_TYPE)
            .limit(limit)
            .threshold(threshold)
            .build());

        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return List.of();
        }

        return response.getResults().stream()
            .map(row -> toSearchHit(row, response))
            .flatMap(Optional::stream)
            .limit(limit)
            .toList();
    }

    public AskResult ask(String question, int limit) {
        List<FaqArticle> matches = semanticSearch(question, limit, 0.3d);
        if (!generationEnabled) {
            return AskResult.searchOnly(question, matches);
        }

        AICoreService aiCoreService = aiCoreServiceProvider.getIfAvailable();
        if (aiCoreService == null) {
            return AskResult.searchOnly(question, matches);
        }

        try {
            String context = buildContext(matches);
            String prompt = """
                You are a customer support assistant.
                Answer the user's question using ONLY the provided FAQ context.
                If the context does not contain the answer, say you don't have enough information and suggest the closest relevant article titles.

                USER QUESTION:
                %s

                FAQ CONTEXT:
                %s
                """.formatted(question, context);

            String answer = aiCoreService.generateText(prompt);
            return AskResult.contextualAnswer(question, answer, matches);
        } catch (Exception ex) {
            log.warn("Generation failed; falling back to search-only", ex);
            return AskResult.searchOnly(question, matches);
        }
    }

    private void index(FaqArticle article, AIProcessOperation operation) {
        AIEntityIndexingGateway indexingGateway = indexingGatewayProvider.getIfAvailable();
        if (indexingGateway == null) {
            log.debug("AIEntityIndexingGateway not available; skipping indexing");
            return;
        }
        indexingGateway.upsert(article, operation);
    }

    private FaqArticle upsertSeedArticle(FaqDemoCatalog.SeedArticle seedArticle) {
        FaqArticle article = repository.findByTitle(seedArticle.title())
            .orElseGet(FaqArticle::new);
        if (article.getCreatedAt() == null) {
            article.setCreatedAt(Instant.now());
        }
        article.setTitle(seedArticle.title());
        article.setContent(seedArticle.content());
        article.setCategory(seedArticle.category());
        article.setTags(seedArticle.tags() == null ? null : String.join(",", seedArticle.tags()));
        article.setUpdatedAt(Instant.now());
        boolean created = article.getId() == null;
        FaqArticle saved = repository.save(article);
        index(
            saved,
            created ? AIProcessOperation.CREATE : AIProcessOperation.UPDATE
        );
        return saved;
    }

    private Optional<SearchHit> toSearchHit(Map<String, Object> row, AISearchResponse response) {
        Optional<String> entityId = extractEntityId(row);
        if (entityId.isEmpty()) {
            return Optional.empty();
        }
        return findArticleByEntityId(entityId.get(), response)
            .map(article -> new SearchHit(article, extractScore(row).orElse(null), entityId.get(), immutableRow(row)));
    }

    private Optional<String> extractEntityId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        return firstPresent(row.get("entityId"), row.get("id"))
            .map(Objects::toString)
            .filter(value -> !value.isBlank());
    }

    private Optional<Object> firstPresent(Object first, Object second) {
        return Optional.ofNullable(first != null ? first : second);
    }

    private Optional<Double> extractScore(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Optional.empty();
        }
        return firstPresent(row.get("score"), firstPresent(row.get("similarity"), row.get("relevanceScore")).orElse(null))
            .flatMap(this::toDouble);
    }

    private Optional<Double> toDouble(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.doubleValue());
        }
        if (value instanceof String text) {
            try {
                return Optional.of(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    private Map<String, Object> immutableRow(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        row.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }

    private Optional<FaqArticle> findArticleByEntityId(String id, AISearchResponse response) {
        try {
            return repository.findById(Long.parseLong(id));
        } catch (NumberFormatException ex) {
            log.debug("Unable to parse faq entityId '{}' as Long (result keys: {})", id, response.getResults().getFirst().keySet());
            return Optional.empty();
        }
    }

    private String buildContext(List<FaqArticle> matches) {
        if (matches == null || matches.isEmpty()) {
            return "(no matching FAQ articles)";
        }
        StringBuilder builder = new StringBuilder();
        for (FaqArticle article : matches) {
            builder.append("TITLE: ").append(article.getTitle()).append("\n");
            builder.append("CATEGORY: ").append(Objects.toString(article.getCategory(), "")).append("\n");
            builder.append("CONTENT: ").append(article.getContent()).append("\n\n");
        }
        return builder.toString();
    }

    public record AskResult(
        String question,
        String mode,
        String answer,
        List<FaqArticle> matches
    ) {
        public static AskResult searchOnly(String question, List<FaqArticle> matches) {
            String answer = matches == null || matches.isEmpty()
                ? "No matching FAQ articles found."
                : "Top matching FAQ articles returned (enable generation to get a composed answer).";
            return new AskResult(question, "SEARCH_ONLY", answer, matches);
        }

        public static AskResult contextualAnswer(String question, String answer, List<FaqArticle> matches) {
            return new AskResult(question, "CONTEXTUAL_GENERATION", answer, matches);
        }
    }

    public record SearchHit(
        FaqArticle article,
        Double score,
        String entityId,
        Map<String, Object> rawResult
    ) {}
}
