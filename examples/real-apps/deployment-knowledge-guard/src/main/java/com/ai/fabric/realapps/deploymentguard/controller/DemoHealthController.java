package com.ai.fabric.realapps.deploymentguard.controller;

import com.ai.fabric.realapps.deploymentguard.service.DeploymentGuardHealthService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoHealthController {

    private final DeploymentGuardHealthService healthService;

    public DemoHealthController(DeploymentGuardHealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return healthService.health();
    }
}
