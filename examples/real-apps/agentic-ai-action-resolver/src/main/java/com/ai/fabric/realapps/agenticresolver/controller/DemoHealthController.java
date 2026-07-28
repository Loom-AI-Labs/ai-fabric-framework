package com.ai.fabric.realapps.agenticresolver.controller;

import com.ai.fabric.realapps.agenticresolver.service.DeploymentInfoService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/demo")
public class DemoHealthController {

    private final DeploymentInfoService deploymentInfoService;

    public DemoHealthController(DeploymentInfoService deploymentInfoService) {
        this.deploymentInfoService = deploymentInfoService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return deploymentInfoService.health();
    }
}
