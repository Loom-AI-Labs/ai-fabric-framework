package com.ai.fabric.realapps.behavior.config;

import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.execution.state.DurableExecutionSecurity;
import ai.fabric.execution.state.JdbcDurableExecutionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class BehaviorDurableStateConfiguration {

    @Bean
    @ConditionalOnMissingBean(DurableExecutionRepository.class)
    DurableExecutionRepository behaviorDurableExecutionRepository(DataSource dataSource) {
        return new JdbcDurableExecutionRepository(dataSource, false);
    }

    @Bean
    @ConditionalOnMissingBean(DurableExecutionSecurity.class)
    DurableExecutionSecurity behaviorDurableExecutionSecurity(
        ObjectMapper objectMapper,
        AIExecutionProperties properties
    ) {
        return new DurableExecutionSecurity(
            objectMapper,
            properties.getAsync().getEncryptionSecret(),
            properties.getAsync().getFingerprintSecret()
        );
    }
}
