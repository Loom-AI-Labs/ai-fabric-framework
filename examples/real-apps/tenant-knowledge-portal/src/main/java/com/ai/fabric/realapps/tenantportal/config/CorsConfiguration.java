package com.ai.fabric.realapps.tenantportal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CorsConfiguration.CorsProperties.class)
class CorsConfiguration implements WebMvcConfigurer {

    private final CorsProperties properties;

    CorsConfiguration(CorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        if (properties.allowedOrigins().isEmpty() && properties.allowedOriginPatterns().isEmpty()) {
            return;
        }

        var registration = registry.addMapping("/**")
            .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
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
    public record CorsProperties(
        List<String> allowedOrigins,
        List<String> allowedOriginPatterns,
        boolean allowCredentials
    ) {
        public CorsProperties {
            allowedOrigins = clean(allowedOrigins);
            allowedOriginPatterns = clean(allowedOriginPatterns);
        }

        private static List<String> clean(List<String> values) {
            return values == null
                ? List.of()
                : values.stream()
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .toList();
        }
    }
}
