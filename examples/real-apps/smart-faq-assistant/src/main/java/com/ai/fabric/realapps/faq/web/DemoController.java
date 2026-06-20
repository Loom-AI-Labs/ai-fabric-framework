package com.ai.fabric.realapps.faq.web;

import com.ai.fabric.realapps.faq.domain.FaqArticle;
import com.ai.fabric.realapps.faq.service.FaqArticleService;
import com.ai.fabric.realapps.faq.service.FaqQualityService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoController {

    private final FaqArticleService faqArticleService;
    private final FaqQualityService faqQualityService;

    @PostMapping("/seed")
    public List<FaqArticle> seed(@RequestBody(required = false) Map<String, Object> ignored) {
        return faqArticleService.seedBaselineArticles();
    }

    @PostMapping("/indexing/reindex/articles")
    public Map<String, Object> reindex(@RequestBody(required = false) Map<String, Object> ignored) {
        int count = faqArticleService.reindexAll();
        return Map.of("indexed", count);
    }

    @PostMapping("/quality/seed-and-run")
    public FaqQualityService.QualityReport seedAndRunQualityGate(
        @RequestBody(required = false) FaqQualityService.QualityRunOptions options
    ) {
        faqArticleService.seedBaselineArticles();
        return faqQualityService.runGoldenSet(options);
    }
}
