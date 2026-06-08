package com.ai.fabric.runtime.config;

import com.ai.fabric.runtime.auth.RuntimePrivateAssertionService;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.auth.RuntimePublicTokenService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RuntimeAuthProperties.class)
public class RuntimeAuthConfiguration {

    @Bean
    RuntimePublicTokenService runtimePublicTokenService(RuntimeAuthProperties properties) {
        return new RuntimePublicTokenService(properties);
    }

    @Bean
    RuntimePrivateAssertionService runtimePrivateAssertionService(RuntimeAuthProperties properties) {
        return new RuntimePrivateAssertionService(properties);
    }

    @Bean
    RuntimeRequestAuthResolver runtimeRequestAuthResolver(RuntimeAuthProperties properties,
                                                          RuntimePrivateAssertionService runtimePrivateAssertionService,
                                                          RuntimePublicTokenService runtimePublicTokenService) {
        return new RuntimeRequestAuthResolver(properties, runtimePrivateAssertionService, runtimePublicTokenService);
    }
}
