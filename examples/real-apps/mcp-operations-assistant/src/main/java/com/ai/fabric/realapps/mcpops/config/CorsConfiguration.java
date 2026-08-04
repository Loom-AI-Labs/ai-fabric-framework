package com.ai.fabric.realapps.mcpops.config;

import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class CorsConfiguration implements WebMvcConfigurer {

    private final String[] origins;

    public CorsConfiguration(
        @Value("${app.cors.allowed-origins:}") String configuredOrigins
    ) {
        this.origins = Arrays.stream(configuredOrigins.split(","))
            .map(String::trim)
            .filter(StringUtils::hasText)
            .toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (origins.length == 0) {
            return;
        }
        registry.addMapping("/api/**")
            .allowedOrigins(origins)
            .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
            .allowedHeaders("Content-Type", "Idempotency-Key")
            .maxAge(3600);
    }
}
