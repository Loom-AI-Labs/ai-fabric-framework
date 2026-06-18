package ai.fabric.pii.config;

import ai.fabric.config.PIIDetectionProperties;
import ai.fabric.intent.orchestration.pipeline.steps.PIIDetectionStep;
import ai.fabric.privacy.pii.DefaultPIIDetectionService;
import ai.fabric.privacy.pii.PIIDetectionService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@EnableConfigurationProperties(PIIDetectionProperties.class)
public class PIIAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(PIIDetectionService.class)
    @ConditionalOnProperty(prefix = "ai.pii-detection", name = "enabled", havingValue = "true")
    public PIIDetectionService piiDetectionService(PIIDetectionProperties properties) {
        return new DefaultPIIDetectionService(properties);
    }

    @Bean
    @ConditionalOnMissingBean(PIIDetectionStep.class)
    @ConditionalOnProperty(prefix = "ai.pii-detection", name = "enabled", havingValue = "true")
    public PIIDetectionStep piiDetectionStep(PIIDetectionService piiDetectionService,
                                             PIIDetectionProperties properties) {
        return new PIIDetectionStep(piiDetectionService, properties);
    }
}
