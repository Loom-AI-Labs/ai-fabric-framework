package com.ai.infrastructure.pii.config;

import com.ai.infrastructure.config.PIIDetectionProperties;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.intent.orchestration.pipeline.steps.PIIDetectionStep;
import com.ai.infrastructure.privacy.pii.DefaultPIIDetectionService;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
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
    @ConditionalOnProperty(prefix = "ai.pii-detection", name = "enabled", havingValue = "true")
    public PipelineStep piiDetectionStep(PIIDetectionService piiDetectionService,
                                         PIIDetectionProperties properties) {
        return new PIIDetectionStep(piiDetectionService, properties);
    }
}

