package com.ai.fabric.realapps.faq.service;

import com.ai.fabric.realapps.faq.domain.FaqArticle;
import com.ai.fabric.realapps.faq.repo.FaqArticleRepository;
import ai.fabric.core.AICoreService;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.service.AICapabilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class FaqArticleService {

    private static final String ENTITY_TYPE = "faq-article";

    private final FaqArticleRepository repository;
    private final ObjectProvider<AICapabilityService> capabilityServiceProvider;
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
        index(saved);
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
        index(saved);
        return saved;
    }

    public List<FaqArticle> list() {
        return repository.findAll();
    }

    public FaqArticle get(long id) {
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public int reindexAll() {
        List<FaqArticle> articles = repository.findAll();
        for (FaqArticle article : articles) {
            index(article);
        }
        return articles.size();
    }

    public List<FaqArticle> semanticSearch(String query, int limit) {
        return semanticSearch(query, limit, 0.3d);
    }

    public List<FaqArticle> semanticSearch(String query, int limit, double threshold) {
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
            .map(this::extractEntityId)
            .filter(Objects::nonNull)
            .map(id -> {
                try {
                    return repository.findById(Long.parseLong(id)).orElse(null);
                } catch (NumberFormatException ex) {
                    log.debug("Unable to parse faq entityId '{}' as Long (result keys: {})", id, response.getResults().getFirst().keySet());
                    return null;
                }
            })
            .filter(Objects::nonNull)
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

    private void index(FaqArticle article) {
        AICapabilityService capabilityService = capabilityServiceProvider.getIfAvailable();
        if (capabilityService == null) {
            log.debug("AICapabilityService not available; skipping indexing");
            return;
        }
        capabilityService.processEntityForAI(article, ENTITY_TYPE);
    }

    private String extractEntityId(Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return null;
        }
        Object id = firstNonNull(row.get("entityId"), row.get("id"));
        return id != null ? Objects.toString(id, null) : null;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
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
}
