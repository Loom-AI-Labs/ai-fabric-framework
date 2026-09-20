package com.ai.fabric.realapps.docingest.web;

import com.ai.fabric.realapps.docingest.service.DocumentIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents/query")
@RequiredArgsConstructor
public class DocumentQueryController {

    private final DocumentIngestionService documentIngestionService;

    @GetMapping
    public DocumentIngestionService.QueryResult query(
        @RequestParam String query,
        @RequestParam String tenantId,
        @RequestParam(defaultValue = "5") int limit
    ) {
        return documentIngestionService.query(query, tenantId, limit);
    }
}
