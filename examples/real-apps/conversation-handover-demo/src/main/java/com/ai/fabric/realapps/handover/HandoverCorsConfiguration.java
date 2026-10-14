package com.ai.fabric.realapps.handover;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(HandoverCorsConfiguration.CorsProperties.class)
class HandoverCorsConfiguration implements WebMvcConfigurer {

    private final CorsProperties properties;

    HandoverCorsConfiguration(CorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (properties.allowedOrigins().isEmpty() && properties.allowedOriginPatterns().isEmpty()) {
            return;
        }

        var registration = registry.addMapping("/api/**")
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);

        if (properties.allowCredentials()) {
            registration.allowCredentials(true);
        }
        if (!properties.allowedOrigins().isEmpty()) {
            registration.allowedOrigins(properties.allowedOrigins().toArray(String[]::new));
        }
        if (!properties.allowedOriginPatterns().isEmpty()) {
            registration.allowedOriginPatterns(properties.allowedOriginPatterns().toArray(String[]::new));
        }
    }

    @ConfigurationProperties(prefix = "app.cors")
    record CorsProperties(
        List<String> allowedOrigins,
        List<String> allowedOriginPatterns,
        boolean allowCredentials
    ) {
        CorsProperties {
            allowedOrigins = allowedOrigins == null ? List.of() : allowedOrigins;
            allowedOriginPatterns = allowedOriginPatterns == null ? List.of() : allowedOriginPatterns;
        }
    }
}
