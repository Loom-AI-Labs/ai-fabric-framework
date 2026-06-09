package com.ai.fabric.realapps.chat.indexing;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.web.client.RestTemplate;

@Configuration
@EnableAsync
@EnableConfigurationProperties(ConnectorIndexingProperties.class)
@ConditionalOnProperty(prefix = "connector.indexing", name = "enabled", havingValue = "true")
public class ConnectorIndexingConfiguration {

    @Bean
    public RestTemplate connectorIndexingRestTemplate(RestTemplateBuilder builder) {
        return builder.build();
    }
}
