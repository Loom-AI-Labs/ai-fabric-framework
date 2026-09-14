package com.ai.fabric.realapps.incident.config;

import com.ai.fabric.realapps.incident.service.IncidentInvocationMetrics;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("smoke")
public class IncidentSmokeProviderConfiguration {

    @Bean
    IncidentSmokeAiProvider incidentSmokeAiProvider(
        IncidentInvocationMetrics metrics,
        ObjectMapper objectMapper
    ) {
        return new IncidentSmokeAiProvider(metrics, objectMapper);
    }
}
