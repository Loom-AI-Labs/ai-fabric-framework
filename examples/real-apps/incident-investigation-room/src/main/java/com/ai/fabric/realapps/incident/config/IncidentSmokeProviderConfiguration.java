package com.ai.fabric.realapps.incident.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("smoke")
public class IncidentSmokeProviderConfiguration {

    @Bean
    IncidentSmokeAiProvider incidentSmokeAiProvider() {
        return new IncidentSmokeAiProvider();
    }
}
