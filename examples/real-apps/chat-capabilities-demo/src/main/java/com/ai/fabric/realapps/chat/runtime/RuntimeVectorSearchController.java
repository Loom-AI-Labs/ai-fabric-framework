package com.ai.fabric.realapps.chat.runtime;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime")
@RequiredArgsConstructor
public class RuntimeVectorSearchController {

    private final RuntimeVectorSearchService searchService;

    @GetMapping("/vector-search")
    public RuntimeVectorSearchService.RuntimeVectorSearchResult search(
        @RequestParam("vectorSpace") String vectorSpace,
        @RequestParam("q") String query,
        @RequestParam(value = "limit", defaultValue = "10") int limit,
        @RequestParam(value = "threshold", defaultValue = "0.0") double threshold
    ) {
        return searchService.search(vectorSpace, query, limit, threshold);
    }
}
