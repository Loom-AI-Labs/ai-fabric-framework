package com.ai.fabric.examples.smoke.health;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.RestController;

@AutoConfiguration
@ConditionalOnClass(RestController.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "ai.fabric.examples.demo-health", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoHealthAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DemoDeploymentInfoService demoDeploymentInfoService(Environment environment) {
        return new DemoDeploymentInfoService(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public DemoHealthController demoHealthController(DemoDeploymentInfoService deploymentInfoService) {
        return new DemoHealthController(deploymentInfoService);
    }
}
