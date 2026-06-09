package com.ai.fabric.realapps.faq.web;

import com.ai.fabric.realapps.faq.domain.FaqArticle;
import com.ai.fabric.realapps.faq.service.FaqArticleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/faq")
@RequiredArgsConstructor
public class FaqController {

    private final FaqArticleService faqArticleService;

    @GetMapping("/articles")
    public List<FaqArticle> list() {
        return faqArticleService.list();
    }

    @GetMapping("/articles/{id}")
    public FaqArticle get(@PathVariable long id) {
        return faqArticleService.get(id);
    }

    @PostMapping("/articles")
    public FaqArticle create(@Valid @RequestBody CreateFaqArticleRequest request) {
        return faqArticleService.create(
            request.title(),
            request.content(),
            request.category(),
            request.tags()
        );
    }

    @GetMapping("/search")
    public List<FaqArticle> search(@RequestParam("q") String query,
                                  @RequestParam(value = "limit", defaultValue = "5") int limit,
                                  @RequestParam(value = "threshold", defaultValue = "0.3") double threshold) {
        return faqArticleService.semanticSearch(query, limit, threshold);
    }

    @PostMapping("/ask")
    public FaqArticleService.AskResult ask(@Valid @RequestBody AskRequest request) {
        int limit = request.limit() != null ? request.limit() : 5;
        return faqArticleService.ask(request.question(), limit);
    }

    public record CreateFaqArticleRequest(
        @NotBlank String title,
        @NotBlank String content,
        String category,
        List<String> tags
    ) {}

    public record AskRequest(
        @NotBlank String question,
        Integer limit
    ) {}
}
