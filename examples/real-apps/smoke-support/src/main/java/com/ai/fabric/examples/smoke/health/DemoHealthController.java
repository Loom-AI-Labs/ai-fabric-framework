package com.ai.fabric.examples.smoke.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoHealthController {

    private final DemoDeploymentInfoService deploymentInfoService;

    public DemoHealthController(DemoDeploymentInfoService deploymentInfoService) {
        this.deploymentInfoService = deploymentInfoService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        return deploymentInfoService.health();
    }
}
