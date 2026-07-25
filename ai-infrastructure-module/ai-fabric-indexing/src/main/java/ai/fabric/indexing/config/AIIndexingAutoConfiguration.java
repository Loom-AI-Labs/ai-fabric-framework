package ai.fabric.indexing.config;

import ai.fabric.aspect.AIProcessAspect;
import ai.fabric.aspect.AIProcessMethodValidator;
import ai.fabric.aspect.AIEntityContractValidator;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIIndexingProperties;
import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.config.condition.EmbeddingsFeatureEnabledCondition;
import ai.fabric.config.condition.VectorDbConfiguredCondition;
import ai.fabric.core.AICoreService;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.indexing.DefaultAIEntityIndexingGateway;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIIndexAnalysisHandler;
import ai.fabric.indexing.descriptor.AIEntityDescriptorInitializer;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.indexing.observability.AIEntityIndexingEndpoint;
import ai.fabric.indexing.observability.IndexingMetrics;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.indexing.worker.AsyncIndexingWorker;
import ai.fabric.indexing.worker.BatchIndexingWorker;
import ai.fabric.indexing.worker.DefaultAIIndexAnalysisHandler;
import ai.fabric.indexing.worker.IndexingCleanupScheduler;
import ai.fabric.indexing.worker.IndexingOperationExecutor;
import ai.fabric.indexing.worker.IndexingWorkProcessor;
import ai.fabric.indexing.worker.SyncIndexingRetryWorker;
import ai.fabric.repository.IndexingEntityStateRepository;
import ai.fabric.repository.IndexingQueueRepository;
import ai.fabric.service.VectorManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import java.time.Clock;

@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@EnableScheduling
@EnableTransactionManagement(order = Ordered.LOWEST_PRECEDENCE - 100)
@ConditionalOnProperty(
    prefix = "ai.indexing",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@Conditional({VectorDbConfiguredCondition.class, EmbeddingsFeatureEnabledCondition.class})
@EnableConfigurationProperties(AIIndexingProperties.class)
public class AIIndexingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public IndexingQueueService indexingQueueService(
        IndexingQueueRepository repository,
        AIIndexingProperties properties,
        ObjectMapper objectMapper,
        Clock clock,
        IndexingMetrics metrics
    ) {
        return new IndexingQueueService(
            repository,
            properties,
            objectMapper,
            clock,
            metrics
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingMetrics indexingMetrics(
        ObjectProvider<MeterRegistry> meterRegistryProvider
    ) {
        return new IndexingMetrics(meterRegistryProvider.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingOperationExecutor indexingOperationExecutor(
        AIEmbeddingService embeddingService,
        VectorManagementService vectorManagementService,
        ObjectProvider<AIIndexAnalysisHandler> analysisHandlerProvider,
        ObjectMapper objectMapper
    ) {
        return new IndexingOperationExecutor(
            embeddingService,
            vectorManagementService,
            analysisHandlerProvider,
            objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingWorkProcessor indexingWorkProcessor(
        IndexingQueueService queueService,
        IndexingEntityStateRepository stateRepository,
        IndexingOperationExecutor operationExecutor,
        Clock clock
    ) {
        return new IndexingWorkProcessor(
            queueService,
            stateRepository,
            operationExecutor,
            clock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public DefaultAIEntityIndexingGateway aiEntityIndexingGateway(
        AIEntityProjectionService projectionService,
        AIEntityDescriptorRegistry descriptorRegistry,
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        IndexingMetrics metrics
    ) {
        return new DefaultAIEntityIndexingGateway(
            projectionService,
            descriptorRegistry,
            queueService,
            workProcessor,
            metrics
        );
    }

    @Bean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.endpoint.annotation.Endpoint")
    @ConditionalOnMissingBean
    public AIEntityIndexingEndpoint aiEntityIndexingEndpoint(
        AIEntityDescriptorRegistry descriptorRegistry,
        IndexingQueueRepository queueRepository,
        ListableBeanFactory beanFactory
    ) {
        return new AIEntityIndexingEndpoint(
            descriptorRegistry,
            queueRepository,
            beanFactory
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AIProcessAspect aiProcessAspect(
        AIEntityIndexingGateway indexingGateway,
        AIEntityDescriptorRegistry descriptorRegistry,
        ListableBeanFactory beanFactory
    ) {
        return new AIProcessAspect(indexingGateway, descriptorRegistry, beanFactory);
    }

    @Bean
    @ConditionalOnMissingBean
    public static AIProcessMethodValidator aiProcessMethodValidator() {
        return new AIProcessMethodValidator();
    }

    @Bean
    @ConditionalOnMissingBean
    public AIEntityContractValidator aiEntityContractValidator(
        ListableBeanFactory beanFactory,
        AIEntityDescriptorRegistry descriptorRegistry,
        AIEntityDescriptorInitializer descriptorInitializer,
        AIEntityConfigurationLoader configurationLoader,
        AIConfiguredEntityProjectionService configuredProjectionService
    ) {
        return new AIEntityContractValidator(
            beanFactory,
            descriptorRegistry,
            descriptorInitializer,
            configurationLoader,
            configuredProjectionService
        );
    }

    @Bean
    @ConditionalOnBean(AICoreService.class)
    @ConditionalOnMissingBean(AIIndexAnalysisHandler.class)
    public AIIndexAnalysisHandler defaultAIIndexAnalysisHandler(
        AICoreService coreService,
        ObjectMapper objectMapper
    ) {
        return new DefaultAIIndexAnalysisHandler(coreService, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public SyncIndexingRetryWorker syncIndexingRetryWorker(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        AIIndexingProperties properties
    ) {
        return new SyncIndexingRetryWorker(queueService, workProcessor, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public AsyncIndexingWorker asyncIndexingWorker(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        AIIndexingProperties properties
    ) {
        return new AsyncIndexingWorker(queueService, workProcessor, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public BatchIndexingWorker batchIndexingWorker(
        IndexingQueueService queueService,
        IndexingWorkProcessor workProcessor,
        AIIndexingProperties properties
    ) {
        return new BatchIndexingWorker(queueService, workProcessor, properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public IndexingCleanupScheduler indexingCleanupScheduler(
        IndexingQueueService queueService,
        AIIndexingProperties properties,
        Clock clock
    ) {
        return new IndexingCleanupScheduler(queueService, properties, clock);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.ai.document.Document")
    @ConditionalOnBean(IndexingQueueService.class)
    static class SpringAiDocumentIndexingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        SpringAiDocumentIndexingAdapter springAiDocumentIndexingAdapter(
            IndexingQueueService queueService,
            AIEntityConfigurationLoader configurationLoader
        ) {
            return new SpringAiDocumentIndexingAdapter(
                queueService,
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
