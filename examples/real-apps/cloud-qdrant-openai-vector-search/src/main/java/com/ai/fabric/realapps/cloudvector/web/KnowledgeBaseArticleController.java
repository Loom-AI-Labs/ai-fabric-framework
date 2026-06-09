package com.ai.fabric.realapps.cloudvector.web;

import com.ai.fabric.realapps.cloudvector.domain.KnowledgeBaseArticle;
import com.ai.fabric.realapps.cloudvector.service.KnowledgeBaseArticleService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/articles")
@RequiredArgsConstructor
public class KnowledgeBaseArticleController {

    private final KnowledgeBaseArticleService service;

    @GetMapping
    public List<KnowledgeBaseArticle> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public KnowledgeBaseArticle get(@PathVariable long id) {
        return service.get(id);
    }

    @PostMapping
    public KnowledgeBaseArticle create(@RequestBody CreateRequest request) {
        return service.create(request.title(), request.content(), request.category(), request.tags());
    }

    @PutMapping("/{id}")
    public KnowledgeBaseArticle update(@PathVariable long id, @RequestBody UpdateRequest request) {
        return service.update(id, request.title(), request.content(), request.category(), request.tags());
    }

    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        return Map.of("indexed", service.reindexAll());
    }

    public record CreateRequest(
        @NotBlank String title,
        @NotBlank String content,
        String category,
        List<String> tags
    ) {}

    public record UpdateRequest(
        String title,
        String content,
        String category,
        List<String> tags
    ) {}
}
