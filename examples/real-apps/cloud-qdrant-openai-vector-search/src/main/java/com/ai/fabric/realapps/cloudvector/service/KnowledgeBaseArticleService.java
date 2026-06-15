package com.ai.fabric.realapps.cloudvector.service;

import com.ai.fabric.realapps.cloudvector.domain.KnowledgeBaseArticle;
import com.ai.fabric.realapps.cloudvector.repo.KnowledgeBaseArticleRepository;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.AICapabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseArticleService {

    public static final String ENTITY_TYPE = "kb-article";

    private final KnowledgeBaseArticleRepository repository;
    private final ObjectProvider<AICapabilityService> capabilityServiceProvider;
    private final ObjectProvider<AICoreService> aiCoreServiceProvider;

    @Transactional
    public KnowledgeBaseArticle create(String title, String content, String category, List<String> tags) {
        KnowledgeBaseArticle article = new KnowledgeBaseArticle();
        article.setTitle(title);
        article.setContent(content);
        article.setCategory(category);
        article.setTags(tags == null ? null : String.join(",", tags));
        article.setCreatedAt(Instant.now());
        article.setUpdatedAt(Instant.now());

        KnowledgeBaseArticle saved = repository.save(article);
        index(saved);
        return saved;
    }

    @Transactional
    public KnowledgeBaseArticle update(long id, String title, String content, String category, List<String> tags) {
        KnowledgeBaseArticle article = repository.findById(id).orElseThrow();
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
        KnowledgeBaseArticle saved = repository.save(article);
        index(saved);
        return saved;
    }

    public List<KnowledgeBaseArticle> list() {
        return repository.findAll();
    }

    public KnowledgeBaseArticle get(long id) {
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public int reindexAll() {
        List<KnowledgeBaseArticle> articles = repository.findAll();
        for (KnowledgeBaseArticle article : articles) {
            index(article);
        }
        return articles.size();
    }

    public List<SearchHit> semanticSearch(String query, int limit, double threshold) {
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
            .map(row -> toHit(row, response.getMaxScore()))
            .filter(Objects::nonNull)
            .toList();
    }

    private void index(KnowledgeBaseArticle article) {
        AICapabilityService capabilityService = capabilityServiceProvider.getIfAvailable();
        if (capabilityService == null) {
            log.debug("AICapabilityService not available; skipping indexing");
            return;
        }
        capabilityService.processEntityForAI(article, ENTITY_TYPE);
    }

    private SearchHit toHit(Map<String, Object> row, Double maxScore) {
        if (row == null || row.isEmpty()) {
            return null;
        }

        Object entityId = row.get("entityId");
        if (entityId == null) {
            return null;
        }

        Long id;
        try {
            id = Long.valueOf(Objects.toString(entityId));
        } catch (NumberFormatException ex) {
            return null;
        }

        KnowledgeBaseArticle article = repository.findById(id).orElse(null);
        if (article == null) {
            return null;
        }

        Double score = null;
        Object rawScore = row.get("score");
        if (rawScore instanceof Number number) {
            score = number.doubleValue();
        }

        String snippet = article.getContent();
        if (snippet != null && snippet.length() > 220) {
            snippet = snippet.substring(0, 220) + "...";
        }

        return new SearchHit(
            id,
            article.getTitle(),
            article.getCategory(),
            score,
            maxScore,
            snippet
        );
    }

    public record SearchHit(
        Long id,
        String title,
        String category,
        Double score,
        Double maxScore,
        String snippet
    ) {}
}
