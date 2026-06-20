package com.ai.fabric.realapps.faq.service;

import ai.fabric.rag.evaluation.springai.SpringAiRagEvaluationService;
import com.ai.fabric.realapps.faq.domain.FaqArticle;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FaqQualityServiceTest {

    private final FaqArticleService faqArticleService = mock(FaqArticleService.class);
    private final ObjectProvider<SpringAiRagEvaluationService> evaluationProvider = unavailableProvider();
    private final FaqQualityService service = new FaqQualityService(faqArticleService, evaluationProvider);

    @Test
    void goldenSetPassesWhenExpectedArticlesAreRetrieved() {
        for (FaqDemoCatalog.GoldenQuestion question : FaqDemoCatalog.goldenQuestions()) {
            when(faqArticleService.searchWithEvidence(eq(question.question()), anyInt(), anyDouble()))
                .thenReturn(List.of(hit(article(question.expectedTitle()), 0.91d)));
        }

        FaqQualityService.QualityReport report = service.runGoldenSet(new FaqQualityService.QualityRunOptions(
            3,
            0.1d,
            false,
            false
        ));

        assertThat(report.pass()).isTrue();
        assertThat(report.passedQuestions()).isEqualTo(report.totalQuestions());
        assertThat(report.mode()).isEqualTo("LOCAL_RETRIEVAL_GOLDEN_SET");
        assertThat(report.springAiEvaluationStatus()).isEqualTo("DISABLED");
        assertThat(report.questions())
            .allSatisfy(question -> assertThat(question.expectedRetrieved()).isTrue());
    }

    @Test
    void goldenSetCanRequireExpectedArticleAsTopResult() {
        FaqDemoCatalog.GoldenQuestion first = FaqDemoCatalog.goldenQuestions().getFirst();
        when(faqArticleService.searchWithEvidence(eq(first.question()), anyInt(), anyDouble()))
            .thenReturn(List.of(
                hit(article("Wrong article"), 0.95d),
                hit(article(first.expectedTitle()), 0.90d)
            ));
        FaqDemoCatalog.goldenQuestions().stream().skip(1).forEach(question ->
            when(faqArticleService.searchWithEvidence(eq(question.question()), anyInt(), anyDouble()))
                .thenReturn(List.of(hit(article(question.expectedTitle()), 0.91d)))
        );

        FaqQualityService.QualityReport report = service.runGoldenSet(new FaqQualityService.QualityRunOptions(
            3,
            0.1d,
            true,
            false
        ));

        assertThat(report.pass()).isFalse();
        assertThat(report.failedQuestions()).isEqualTo(1);
        assertThat(report.questions().getFirst().expectedRetrieved()).isTrue();
        assertThat(report.questions().getFirst().topExpected()).isFalse();
    }

    @Test
    void springAiEvaluationReportsUnavailableWhenRequestedButNotConfigured() {
        for (FaqDemoCatalog.GoldenQuestion question : FaqDemoCatalog.goldenQuestions()) {
            when(faqArticleService.searchWithEvidence(eq(question.question()), anyInt(), anyDouble()))
                .thenReturn(List.of(hit(article(question.expectedTitle()), 0.91d)));
        }

        FaqQualityService.QualityReport report = service.runGoldenSet(new FaqQualityService.QualityRunOptions(
            3,
            0.1d,
            false,
            true
        ));

        assertThat(report.springAiEvaluationStatus()).isEqualTo("UNAVAILABLE");
        assertThat(report.questions())
            .allSatisfy(question -> assertThat(question.springEvaluation().status()).isEqualTo("UNAVAILABLE"));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> unavailableProvider() {
        ObjectProvider<T> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        return provider;
    }

    private static FaqArticleService.SearchHit hit(FaqArticle article, double score) {
        return new FaqArticleService.SearchHit(
            article,
            score,
            String.valueOf(article.getId()),
            Map.of("entityId", String.valueOf(article.getId()), "score", score)
        );
    }

    private static FaqArticle article(String title) {
        FaqArticle article = new FaqArticle();
        article.setId((long) Math.abs(title.hashCode()));
        article.setTitle(title);
        article.setContent(title + " content");
        article.setCategory("Demo");
        article.setTags("demo");
        return article;
    }
}
