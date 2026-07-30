package com.ai.fabric.realapps.retrievallab.web;

import com.ai.fabric.realapps.retrievallab.service.RetrievalBoundaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/retrieval-boundary")
public class RetrievalBoundaryController {

    private final RetrievalBoundaryService service;

    public RetrievalBoundaryController(
        RetrievalBoundaryService service
    ) {
        this.service = service;
    }

    @GetMapping("/scenarios")
    public Map<String, Object> scenarios() {
        return Map.of(
            "scenarios",
            List.of(
                "VALID",
                "TENANT_DENIAL",
                "GENERATED_ANSWER_INJECTION",
                "CROSS_VECTOR_SPACE",
                "UNSAFE_URL",
                "RESERVED_METADATA"
            ),
            "fixture",
            "POST /fixture/retrieval/search",
            "generationPolicy",
            "Generation runs only after evidence passes AI Fabric policy."
        );
    }

    @PostMapping("/run")
    public RetrievalBoundaryService.BoundaryOutcome run(
        @RequestBody(required = false)
        RetrievalBoundaryService.BoundaryRequest request
    ) {
        return service.run(request);
    }
}
