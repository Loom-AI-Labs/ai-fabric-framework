package ai.fabric.behavior.config;

import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.dto.AIEntityIndexingPolicy;
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
@AutoConfigurationPackage(basePackages = "ai.fabric.behavior")
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@AutoConfigureBefore({
    org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
})
@ConditionalOnProperty(prefix = "ai.behavior", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@DependsOn("AIEntityConfigurationLoader")
@ComponentScan(basePackages = "ai.fabric.behavior")
@EnableScheduling
public class BehaviorAIAutoConfiguration {
    
    private final AIEntityConfigurationLoader frameworkConfigLoader;
    
    @Value("${ai.behavior.mode:LIGHT}")
    private String mode;
    
    @PostConstruct
    public void registerBehaviorConfig() {
        if (frameworkConfigLoader.hasEntityConfig("behavior-insight")) {
            log.info(
                "Behavior entity policy supplied by application Config Data (mode: {})",
                mode
            );
            return;
        }
        boolean indexingEnabled = "FULL".equalsIgnoreCase(mode);
        AIEntityConfig config = new AIEntityConfig();
        AIEntityIndexingPolicy indexing = new AIEntityIndexingPolicy();
        indexing.setEnabled(indexingEnabled);
        indexing.setMaxCharacters(8000);
        config.setIndexing(indexing);
        frameworkConfigLoader.registerEntityConfig(
            "behavior-insight",
            config,
            false
        );
        log.info(
            "Behavior AI Addon ready (mode: {}, indexingEnabled: {})",
            mode,
            indexingEnabled
        );
    }
}
