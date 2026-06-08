package com.ai.infrastructure.intent.action.connector.registry.config;

import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.intent.action.connector.registry.AIActionDbRegistryProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@EnableConfigurationProperties(AIActionDbRegistryProperties.class)
public class AIActionDbRegistryAutoConfiguration {
}

