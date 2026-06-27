package ai.fabric.indexing.config;

import ai.fabric.aspect.AICapableAspect;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.condition.EmbeddingsFeatureEnabledCondition;
import ai.fabric.config.condition.VectorDbConfiguredCondition;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.indexing.IndexingCoordinator;
import ai.fabric.indexing.IndexingStrategyResolver;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.indexing.worker.AsyncIndexingWorker;
import ai.fabric.indexing.worker.BatchIndexingWorker;
import ai.fabric.indexing.worker.IndexingCleanupScheduler;
import ai.fabric.indexing.worker.IndexingWorkProcessor;
import ai.fabric.repository.IndexingQueueRepository;
import ai.fabric.service.AICapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
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
        AICapabilityService capabilityService,
        Clock clock
    ) {
        return new IndexingCoordinator(
            indexingStrategyResolver,
            indexingQueueService,
            configurationLoader,
            indexingProperties,
            objectMapper,
            capabilityService,
            clock
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

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.ai.document.Document")
    @ConditionalOnProperty(prefix = "ai.indexing", name = "enabled", havingValue = "true", matchIfMissing = true)
    @Conditional({VectorDbConfiguredCondition.class, EmbeddingsFeatureEnabledCondition.class})
    static class SpringAiDocumentIndexingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SpringAiDocumentIndexingAdapter springAiDocumentIndexingAdapter(
            ObjectMapper objectMapper,
            IndexingQueueService indexingQueueService,
            AIEntityConfigurationLoader configurationLoader
        ) {
            return new SpringAiDocumentIndexingAdapter(
                objectMapper,
                indexingQueueService,
                configurationLoader
            );
        }

        @Bean
        @ConditionalOnMissingBean
        SpringAiDocumentReaderFactory springAiDocumentReaderFactory() {
            return new SpringAiDocumentReaderFactory();
        }
    }
}
