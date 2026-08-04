package com.ai.fabric.examples.smoke.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/demo")
public class DemoHealthController {

    private final DemoDeploymentInfoService deploymentInfoService;
    private final List<DemoHealthContributor> contributors;

    public DemoHealthController(DemoDeploymentInfoService deploymentInfoService) {
        this(deploymentInfoService, List.of());
    }

    public DemoHealthController(
        DemoDeploymentInfoService deploymentInfoService,
        List<DemoHealthContributor> contributors
    ) {
        this.deploymentInfoService = deploymentInfoService;
        this.contributors = contributors == null
            ? List.of()
            : List.copyOf(contributors);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> health = new LinkedHashMap<>(
            deploymentInfoService.health()
        );
        contributors.forEach(contributor -> {
            Map<String, Object> details = contributor.details();
            if (details != null) {
                health.putAll(details);
            }
        });
        return Map.copyOf(health);
    }
}
