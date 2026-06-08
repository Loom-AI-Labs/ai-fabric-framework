package com.ai.infrastructure.intent.action.connector.config;

import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.intent.action.connector.AIActionCatalogProperties;
import com.ai.infrastructure.intent.action.connector.AIActionConnectorProperties;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Auto-configuration for connector-backed actions.
 *
 * <p>This module provides:</p>
 * <ul>
 *   <li>file-based connector action catalogs (V1)</li>
 *   <li>connector execution via the Customer Connector API</li>
 * </ul>
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@EnableConfigurationProperties({
    AIActionCatalogProperties.class,
    AIActionConnectorProperties.class
})
public class AIActionConnectorAutoConfiguration {
}

