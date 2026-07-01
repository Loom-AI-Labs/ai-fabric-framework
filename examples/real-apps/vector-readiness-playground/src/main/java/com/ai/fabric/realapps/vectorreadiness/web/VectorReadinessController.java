package com.ai.fabric.realapps.vectorreadiness.web;

import com.ai.fabric.realapps.vectorreadiness.service.VectorReadinessService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/vector-readiness")
@RequiredArgsConstructor
public class VectorReadinessController {

    private final VectorReadinessService service;

    @GetMapping
    public VectorReadinessService.ReadinessReport readiness() {
        return service.readiness();
    }

    @PostMapping("/lifecycle")
    public VectorReadinessService.LifecycleRunResult lifecycle() {
        return service.runLifecycle();
    }
}
