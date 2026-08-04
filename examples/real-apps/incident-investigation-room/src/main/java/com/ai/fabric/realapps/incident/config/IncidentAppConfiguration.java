package com.ai.fabric.realapps.incident.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IncidentAppConfiguration {

    @Bean
    Clock incidentClock() {
        return Clock.systemUTC();
    }
}
