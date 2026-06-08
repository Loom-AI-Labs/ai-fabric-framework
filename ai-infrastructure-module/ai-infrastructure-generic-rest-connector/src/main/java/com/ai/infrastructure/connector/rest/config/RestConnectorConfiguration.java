package com.ai.infrastructure.connector.rest.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class RestConnectorConfiguration {

    @Bean
    public RestRoutingConfig restRoutingConfig(RestRoutingConfigLoader loader, RestConnectorServiceProperties properties) {
        String location = properties != null ? properties.getRoutingConfigLocation() : null;
        return loader.load(location);
    }

    @Bean
    public Clock systemClock() {
        return Clock.systemUTC();
    }
}
