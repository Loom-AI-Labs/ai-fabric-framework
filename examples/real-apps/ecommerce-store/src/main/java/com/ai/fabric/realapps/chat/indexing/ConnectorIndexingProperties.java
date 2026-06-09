package com.ai.fabric.realapps.chat.indexing;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "connector.indexing")
public record ConnectorIndexingProperties(
    boolean enabled,
    String runtimeBaseUrl,
    String apiKey,
    String apiKeyHeader
) {
}
