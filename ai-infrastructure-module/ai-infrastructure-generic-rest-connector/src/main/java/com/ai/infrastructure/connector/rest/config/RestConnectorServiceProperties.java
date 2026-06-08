package com.ai.infrastructure.connector.rest.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "rest-connector")
public class RestConnectorServiceProperties {

    /**
     * Location of the routing YAML file. Supports Spring resource syntax such as
     * {@code classpath:}, {@code file:}, and absolute {@code http(s)} URLs.
     */
    private String routingConfigLocation = "classpath:actions-routing.yml";
}
