package com.ai.fabric.runtime.config;

import com.ai.fabric.runtime.authz.AllowVerifiedEntityAccessPolicy;
import com.ai.fabric.runtime.authz.RemoteHttpEntityAccessPolicy;
import com.ai.infrastructure.access.policy.EntityAccessPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Runtime-only wiring for product authorization.
 *
 * <p>Design goals:
 * <ul>
 *   <li>Fail-closed by default (no implicit allow-all).</li>
 *   <li>Support "no customer code" deployments via a remote HTTP policy.</li>
 *   <li>Always let customer-provided {@link EntityAccessPolicy} beans override.</li>
 * </ul></p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeAuthzProperties.class)
class RuntimeAuthzConfiguration {

    @Bean
    @ConditionalOnMissingBean(EntityAccessPolicy.class)
    @ConditionalOnProperty(prefix = "ai.fabric.runtime.authz", name = "mode", havingValue = "REMOTE_HTTP", matchIfMissing = true)
    EntityAccessPolicy remoteHttpEntityAccessPolicy(RuntimeAuthzProperties properties,
                                                    ObjectMapper objectMapper) {
        return new RemoteHttpEntityAccessPolicy(properties, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean(EntityAccessPolicy.class)
    @ConditionalOnProperty(prefix = "ai.fabric.runtime.authz", name = "mode", havingValue = "ALLOW_VERIFIED")
    EntityAccessPolicy allowVerifiedEntityAccessPolicy() {
        return new AllowVerifiedEntityAccessPolicy();
    }

    @Bean
    @ConditionalOnMissingBean(EntityAccessPolicy.class)
    @ConditionalOnProperty(prefix = "ai.fabric.runtime.authz", name = "mode", havingValue = "DENY_ALL")
    EntityAccessPolicy denyAllEntityAccessPolicy() {
        return (authContext, entity) -> false;
    }
}
