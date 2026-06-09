package com.ai.fabric.realapps.cloudvector.web;

import com.ai.fabric.realapps.cloudvector.service.KnowledgeBaseArticleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
public class SearchController {

    private final KnowledgeBaseArticleService service;

    @GetMapping
    public List<KnowledgeBaseArticleService.SearchHit> search(@RequestParam("q") String query,
                                                              @RequestParam(value = "limit", defaultValue = "5") Integer limit,
                                                              @RequestParam(value = "threshold", defaultValue = "0.3") Double threshold) {
        return service.semanticSearch(query, limit, threshold);
    }
}
