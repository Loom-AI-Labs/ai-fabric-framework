package com.ai.infrastructure.indexing.config;

import com.ai.infrastructure.aspect.AICapableAspect;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.config.AIIndexingProperties;
import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.config.condition.EmbeddingsFeatureEnabledCondition;
import com.ai.infrastructure.config.condition.VectorDbConfiguredCondition;
import com.ai.infrastructure.indexing.IndexingCoordinator;
import com.ai.infrastructure.indexing.IndexingStrategyResolver;
import com.ai.infrastructure.indexing.queue.IndexingQueueService;
import com.ai.infrastructure.indexing.worker.AsyncIndexingWorker;
import com.ai.infrastructure.indexing.worker.BatchIndexingWorker;
import com.ai.infrastructure.indexing.worker.IndexingCleanupScheduler;
import com.ai.infrastructure.indexing.worker.IndexingWorkProcessor;
import com.ai.infrastructure.repository.IndexingQueueRepository;
import com.ai.infrastructure.service.AICapabilityService;
import com.ai.infrastructure.service.VectorManagementService;
import java.time.Clock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.context.annotation.Conditional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@EnableScheduling
@ConditionalOnProperty(prefix = "ai.indexing", name = "enabled", havingValue = "true", matchIfMissing = true)
@Conditional({VectorDbConfiguredCondition.class, EmbeddingsFeatureEnabledCondition.class})
@EnableConfigurationProperties({AIIndexingProperties.class})
public class AIIndexingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IndexingStrategyResolver indexingStrategyResolver() {
        return new IndexingStrategyResolver();
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingQueueService indexingQueueService(
        IndexingQueueRepository repository,
        AIIndexingProperties indexingProperties,
        Clock clock
    ) {
        return new IndexingQueueService(repository, indexingProperties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingWorkProcessor indexingWorkProcessor(
        ObjectMapper objectMapper,
        AIEntityConfigurationLoader configurationLoader,
        AICapabilityService capabilityService
    ) {
        return new IndexingWorkProcessor(objectMapper, configurationLoader, capabilityService);
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingCoordinator indexingCoordinator(
        IndexingStrategyResolver indexingStrategyResolver,
        IndexingQueueService indexingQueueService,
        AIEntityConfigurationLoader configurationLoader,
        AIIndexingProperties indexingProperties,
        ObjectMapper objectMapper,
        AICapabilityService capabilityService
    ) {
        return new IndexingCoordinator(
            indexingStrategyResolver,
            indexingQueueService,
            configurationLoader,
            indexingProperties,
            objectMapper,
            capabilityService
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncIndexingWorker asyncIndexingWorker(
        IndexingQueueService indexingQueueService,
        IndexingWorkProcessor indexingWorkProcessor,
        AIIndexingProperties indexingProperties
    ) {
        return new AsyncIndexingWorker(indexingQueueService, indexingWorkProcessor, indexingProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public BatchIndexingWorker batchIndexingWorker(
        IndexingQueueService indexingQueueService,
        IndexingWorkProcessor indexingWorkProcessor,
        AIIndexingProperties indexingProperties
    ) {
        return new BatchIndexingWorker(indexingQueueService, indexingWorkProcessor, indexingProperties);
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingCleanupScheduler indexingCleanupScheduler(
        IndexingQueueService indexingQueueService,
        AIIndexingProperties indexingProperties,
        Clock clock
    ) {
        return new IndexingCleanupScheduler(indexingQueueService, indexingProperties, clock);
    }

    @Bean
    @ConditionalOnMissingBean
    public AICapableAspect aiCapableAspect(
        AIEntityConfigurationLoader configLoader,
        AICapabilityService aiCapabilityService,
        IndexingCoordinator indexingCoordinator
    ) {
        return new AICapableAspect(configLoader, aiCapabilityService, indexingCoordinator);
    }
}
