package com.ai.fabric.realapps.chat.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Demo-only access + compliance configuration.
 *
 * <p>Production deployments should implement strict access control and compliance checks.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DemoAccessAndComplianceConfiguration {

    @Bean
    EntityAccessPolicy demoEntityAccessPolicy() {
        return (userId, entity) -> true;
    }

    @Bean
    ComplianceCheckProvider demoComplianceCheckProvider() {
        return request -> ComplianceCheckResult.builder()
            .compliant(true)
            .details("Demo compliance provider approval")
            .build();
    }
}
