package com.ai.fabric.realapps.chat.config;

import com.ai.infrastructure.access.policy.EntityAccessPolicy;
import com.ai.infrastructure.compliance.policy.ComplianceCheckProvider;
import com.ai.infrastructure.compliance.policy.ComplianceCheckResult;
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
