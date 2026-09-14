package com.ai.fabric.realapps.agenticresolver.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("smoke")
public class AccountResolverSmokeProviderConfiguration {

    @Bean
    AccountResolverSmokeAiProvider accountResolverSmokeAiProvider(
        ObjectMapper objectMapper
    ) {
        return new AccountResolverSmokeAiProvider(objectMapper);
    }
}
