package com.ai.infrastructure.connector.rest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.admin")
public class RestConnectorAdminAuthProperties {

    private String apiKey;

    private String apiKeyHeader = "X-ADMIN-API-KEY";
}
