package com.ai.infrastructure.web.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
@ConditionalOnWebApplication
@ConditionalOnClass(name = "org.springframework.web.servlet.DispatcherServlet")
@ConditionalOnProperty(prefix = "ai.web", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(AIWebProperties.class)
public class AIWebAutoConfiguration {
    
    public AIWebAutoConfiguration() {
        log.info("AI Infrastructure Web AutoConfiguration initialized - 59 REST endpoints available");
    }
}
