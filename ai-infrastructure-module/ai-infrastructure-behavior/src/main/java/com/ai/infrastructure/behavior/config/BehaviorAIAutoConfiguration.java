package com.ai.infrastructure.behavior.config;

import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@AutoConfiguration
@AutoConfigurationPackage(basePackages = "com.ai.infrastructure.behavior")
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@AutoConfigureBefore({
    org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "ai.behavior", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@DependsOn("AIEntityConfigurationLoader")
@ComponentScan(basePackages = "com.ai.infrastructure.behavior")
@EnableScheduling
public class BehaviorAIAutoConfiguration {
    
    private final AIEntityConfigurationLoader frameworkConfigLoader;
    
    @Value("${ai.behavior.mode:LIGHT}")
    private String mode;
    
    @PostConstruct
    public void registerBehaviorConfig() {
        String presetFile = "classpath:behavior-presets/behavior-ai-" + mode.toLowerCase() + ".yml";
        
        log.info("Registering Behavior Module preset configuration (mode: {})", mode);
        
        frameworkConfigLoader.loadConfigurationFromFile(presetFile, false);
        
        log.info("Behavior AI Addon ready (mode: {})", mode);
    }
}
