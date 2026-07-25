package ai.fabric.datasync.config;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.indexing.config.AIIndexingAutoConfiguration;
import ai.fabric.config.condition.VectorDbConfiguredCondition;
import ai.fabric.datasync.AIDataSyncProperties;
import ai.fabric.datasync.controller.DataSyncController;
import ai.fabric.datasync.normalize.DataSyncEntityNormalizer;
import ai.fabric.datasync.service.DataSyncService;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
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
@AutoConfigureAfter({
    AIInfrastructureAutoConfiguration.class,
    AIIndexingAutoConfiguration.class
})
@EnableConfigurationProperties(AIDataSyncProperties.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnExpression("${ai.data-sync.enabled:false} && ${ai.service.features.enable-embeddings:true}")
@Conditional(VectorDbConfiguredCondition.class)
public class AIDataSyncAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DataSyncEntityNormalizer dataSyncEntityNormalizer(AIDataSyncProperties properties,
                                                             AIConfiguredEntityProjectionService projectionService) {
        return new DataSyncEntityNormalizer(properties, projectionService);
    }

    @Bean
    @ConditionalOnMissingBean
    public DataSyncService dataSyncService(AIDataSyncProperties properties,
                                           AIEntityConfigurationLoader entityConfigurationLoader,
                                           AIEntityIndexingGateway indexingGateway,
                                           AIAccessControlService accessControlService,
                                           DataSyncEntityNormalizer normalizer,
                                           ObjectProvider<Clock> clockProvider) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new DataSyncService(
            properties,
            entityConfigurationLoader,
            indexingGateway,
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
