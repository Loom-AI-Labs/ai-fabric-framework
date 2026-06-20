package com.ai.fabric.realapps.faq.service;

import ai.fabric.dto.RAGResponse;
import ai.fabric.rag.evaluation.springai.SpringAiRagEvaluationInput;
import ai.fabric.rag.evaluation.springai.SpringAiRagEvaluationResult;
import ai.fabric.rag.evaluation.springai.SpringAiRagEvaluationService;
import com.ai.fabric.realapps.faq.domain.FaqArticle;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class FaqQualityService {

    private static final int DEFAULT_LIMIT = 5;
    private static final double DEFAULT_THRESHOLD = 0.05d;

    private final FaqArticleService faqArticleService;
    private final ObjectProvider<SpringAiRagEvaluationService> ragEvaluationServiceProvider;

    public List<FaqDemoCatalog.GoldenQuestion> goldenQuestions() {
        return FaqDemoCatalog.goldenQuestions();
    }

    public QualityReport runGoldenSet(QualityRunOptions options) {
        QualityRunOptions effectiveOptions = options != null ? options : new QualityRunOptions(null, null, null, null);
        int limit = normalizeLimit(effectiveOptions.limit());
        double threshold = normalizeThreshold(effectiveOptions.threshold());
        boolean requireTopMatch = Boolean.TRUE.equals(effectiveOptions.requireTopMatch());
        boolean springAiEvaluationRequested = Boolean.TRUE.equals(effectiveOptions.springAiEvaluation());
        SpringAiRagEvaluationService evaluationService = springAiEvaluationRequested
            ? ragEvaluationServiceProvider.getIfAvailable()
            : null;

        List<QuestionResult> questionResults = goldenQuestions().stream()
            .map(question -> evaluateQuestion(question, limit, threshold, requireTopMatch, springAiEvaluationRequested, evaluationService))
            .toList();

        int passed = (int) questionResults.stream().filter(QuestionResult::passed).count();
        int failed = questionResults.size() - passed;
        return new QualityReport(
            failed == 0,
            "LOCAL_RETRIEVAL_GOLDEN_SET",
            questionResults.size(),
            passed,
            failed,
            questionResults.isEmpty() ? 0.0d : passed / (double) questionResults.size(),
            limit,
            threshold,
            requireTopMatch,
            springAiEvaluationRequested,
            springAiEvaluationStatus(springAiEvaluationRequested, evaluationService),
            questionResults
        );
    }

    private QuestionResult evaluateQuestion(FaqDemoCatalog.GoldenQuestion question,
                                            int limit,
                                            double threshold,
                                            boolean requireTopMatch,
                                            boolean springAiEvaluationRequested,
                                            SpringAiRagEvaluationService evaluationService) {
        List<FaqArticleService.SearchHit> hits = faqArticleService.searchWithEvidence(question.question(), limit, threshold);
        List<RetrievedArticle> retrievedArticles = hits.stream()
            .map(hit -> toRetrievedArticle(hit, question.expectedTitle()))
            .toList();

        boolean expectedRetrieved = retrievedArticles.stream().anyMatch(RetrievedArticle::expectedMatch);
        boolean topExpected = !retrievedArticles.isEmpty() && retrievedArticles.getFirst().expectedMatch();
        boolean passed = requireTopMatch ? topExpected : expectedRetrieved;
        SpringEvaluation springEvaluation = evaluateWithSpringAi(
            question,
            hits,
            springAiEvaluationRequested,
            evaluationService
        );

        return new QuestionResult(
            question.id(),
            question.question(),
            question.expectedTitle(),
            passed,
            expectedRetrieved,
            topExpected,
            retrievedArticles,
            springEvaluation,
            feedback(expectedRetrieved, topExpected, requireTopMatch, question.expectedTitle())
        );
    }

    private RetrievedArticle toRetrievedArticle(FaqArticleService.SearchHit hit, String expectedTitle) {
        FaqArticle article = hit.article();
        return new RetrievedArticle(
            article.getId(),
            article.getTitle(),
            article.getCategory(),
            article.getTags(),
            hit.score(),
            titlesMatch(article.getTitle(), expectedTitle),
            safeEvidenceMetadata(hit.rawResult())
        );
    }

    private SpringEvaluation evaluateWithSpringAi(FaqDemoCatalog.GoldenQuestion question,
                                                  List<FaqArticleService.SearchHit> hits,
                                                  boolean requested,
                                                  SpringAiRagEvaluationService evaluationService) {
        if (!requested) {
            return SpringEvaluation.disabled();
        }
        if (evaluationService == null) {
            return SpringEvaluation.unavailable("Spring AI RAG evaluation is not configured");
        }
        if (hits == null || hits.isEmpty()) {
            return SpringEvaluation.unavailable("No retrieved documents are available for Spring AI evaluation");
        }

        RAGResponse ragResponse = toRagResponse(question, hits);
        SpringAiRagEvaluationResult relevancy = evaluationService.evaluateRelevancy(
            SpringAiRagEvaluationInput.forRetrievedContext(question.question(), ragResponse)
        );
        SpringAiRagEvaluationResult factChecking = evaluationService.evaluateFactChecking(
            SpringAiRagEvaluationInput.forRetrievedContext(question.question(), ragResponse)
        );
        return SpringEvaluation.ran(relevancy, factChecking);
    }

    private RAGResponse toRagResponse(FaqDemoCatalog.GoldenQuestion question, List<FaqArticleService.SearchHit> hits) {
        List<RAGResponse.RAGDocument> documents = hits.stream()
            .map(hit -> {
                FaqArticle article = hit.article();
                return RAGResponse.RAGDocument.builder()
                    .id(article.getId() != null ? article.getId().toString() : hit.entityId())
                    .title(article.getTitle())
                    .content(article.getContent())
                    .type("faq-article")
                    .score(hit.score())
                    .similarity(hit.score())
                    .source("smart-faq-assistant")
                    .metadata(Map.of(
                        "category", Objects.toString(article.getCategory(), ""),
                        "tags", Objects.toString(article.getTags(), "")
                    ))
                    .build();
            })
            .toList();

        return RAGResponse.builder()
            .documents(documents)
            .context(buildContext(hits))
            .totalDocuments(documents.size())
            .usedDocuments(documents.size())
            .totalResults(documents.size())
            .returnedResults(documents.size())
            .relevanceScores(hits.stream().map(FaqArticleService.SearchHit::score).filter(Objects::nonNull).toList())
            .success(true)
            .originalQuery(question.question())
            .entityType("faq-article")
            .timestamp(LocalDateTime.now())
            .metadata(Map.of("goldenQuestionId", question.id()))
            .build();
    }

    private String buildContext(List<FaqArticleService.SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (FaqArticleService.SearchHit hit : hits) {
            FaqArticle article = hit.article();
            builder.append("TITLE: ").append(article.getTitle()).append("\n");
            builder.append("CONTENT: ").append(article.getContent()).append("\n\n");
        }
        return builder.toString().trim();
    }

    private Map<String, Object> safeEvidenceMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        metadata.forEach((key, value) -> {
            if (!StringUtils.hasText(key) || value == null || safe.size() >= 12) {
                return;
            }
            String normalizedKey = key.toLowerCase(Locale.ROOT);
            if (normalizedKey.contains("embedding") || normalizedKey.contains("prompt")
                || normalizedKey.contains("token") || normalizedKey.contains("secret")) {
                return;
            }
            if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                safe.put(key, value);
            }
        });
        return Map.copyOf(safe);
    }

    private String feedback(boolean expectedRetrieved, boolean topExpected, boolean requireTopMatch, String expectedTitle) {
        if (requireTopMatch && topExpected) {
            return "Expected FAQ article was the top retrieval result.";
        }
        if (!requireTopMatch && expectedRetrieved) {
            return "Expected FAQ article was retrieved.";
        }
        if (expectedRetrieved) {
            return "Expected FAQ article was retrieved, but not as the top result.";
        }
        return "Expected FAQ article was not retrieved: " + expectedTitle;
    }

    private String springAiEvaluationStatus(boolean requested, SpringAiRagEvaluationService evaluationService) {
        if (!requested) {
            return "DISABLED";
        }
        return evaluationService == null ? "UNAVAILABLE" : "AVAILABLE";
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.max(1, Math.min(20, limit));
    }

    private double normalizeThreshold(Double threshold) {
        if (threshold == null) {
            return DEFAULT_THRESHOLD;
        }
        return Math.max(0.0d, Math.min(1.0d, threshold));
    }

    private boolean titlesMatch(String actual, String expected) {
        return normalizeTitle(actual).equals(normalizeTitle(expected));
    }

    private String normalizeTitle(String title) {
        return title == null ? "" : title.trim().toLowerCase(Locale.ROOT);
    }

    public record QualityRunOptions(
        Integer limit,
        Double threshold,
        Boolean requireTopMatch,
        Boolean springAiEvaluation
    ) {}

    public record QualityReport(
        boolean pass,
        String mode,
        int totalQuestions,
        int passedQuestions,
        int failedQuestions,
        double passRate,
        int limit,
        double threshold,
        boolean requireTopMatch,
        boolean springAiEvaluationRequested,
        String springAiEvaluationStatus,
        List<QuestionResult> questions
    ) {}

    public record QuestionResult(
        String id,
        String question,
        String expectedTitle,
        boolean passed,
        boolean expectedRetrieved,
        boolean topExpected,
        List<RetrievedArticle> retrievedArticles,
        SpringEvaluation springEvaluation,
        String feedback
    ) {}

    public record RetrievedArticle(
        Long id,
        String title,
        String category,
        String tags,
        Double score,
        boolean expectedMatch,
        Map<String, Object> evidence
    ) {}

    public record SpringEvaluation(
        String status,
        SpringAiRagEvaluationResult relevancy,
        SpringAiRagEvaluationResult factChecking,
        String message
    ) {
        static SpringEvaluation disabled() {
            return new SpringEvaluation("DISABLED", null, null, "Spring AI evaluation was not requested.");
        }

        static SpringEvaluation unavailable(String message) {
            return new SpringEvaluation("UNAVAILABLE", null, null, message);
        }

        static SpringEvaluation ran(SpringAiRagEvaluationResult relevancy,
                                    SpringAiRagEvaluationResult factChecking) {
            return new SpringEvaluation("RAN", relevancy, factChecking, "Spring AI evaluation completed.");
        }
    }
}
