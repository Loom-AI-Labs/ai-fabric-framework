package com.ai.infrastructure.datasync.config;

import com.ai.infrastructure.access.AIAccessControlService;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.config.condition.VectorDbConfiguredCondition;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.datasync.AIDataSyncProperties;
import com.ai.infrastructure.datasync.controller.DataSyncController;
import com.ai.infrastructure.datasync.normalize.DataSyncEntityNormalizer;
import com.ai.infrastructure.datasync.service.DataSyncService;
import com.ai.infrastructure.service.VectorManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;

import java.time.Clock;

/**
 * Auto-configuration for the push-based data sync (ingestion) API.
 */
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@EnableConfigurationProperties(AIDataSyncProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression("${ai.data-sync.enabled:false} && ${ai.service.features.enable-embeddings:true}")
@Conditional(VectorDbConfiguredCondition.class)
public class AIDataSyncAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSyncEntityNormalizer dataSyncEntityNormalizer(AIDataSyncProperties properties,
                                                             ObjectProvider<ObjectMapper> objectMapperProvider) {
        return new DataSyncEntityNormalizer(properties, objectMapperProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataSyncService dataSyncService(AIDataSyncProperties properties,
                                           AIEntityConfigurationLoader entityConfigurationLoader,
                                           AIEmbeddingService embeddingService,
                                           VectorManagementService vectorManagementService,
                                           AIAccessControlService accessControlService,
                                           DataSyncEntityNormalizer normalizer,
                                           Clock clock) {
        return new DataSyncService(
            properties,
            entityConfigurationLoader,
            embeddingService,
            vectorManagementService,
            accessControlService,
            normalizer,
            clock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DataSyncController dataSyncController(DataSyncService dataSyncService) {
        return new DataSyncController(dataSyncService);
    }
}
