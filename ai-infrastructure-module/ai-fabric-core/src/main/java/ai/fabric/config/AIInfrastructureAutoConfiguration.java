package ai.fabric.config;

import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.intent.orchestration.pipeline.DefaultOrchestrationPipeline;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.processor.EmbeddingProcessor;
import ai.fabric.indexing.api.AIEntityDescriptorContributor;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.EntityIdentityResolver;
import ai.fabric.indexing.descriptor.AIEntityDescriptorInitializer;
import ai.fabric.indexing.descriptor.AIEntityDescriptorRegistry;
import ai.fabric.indexing.projection.AIEntityProjectionService;
import ai.fabric.indexing.projection.AIConfiguredEntityProjectionService;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.service.VectorManagementService;
import ai.fabric.security.AISecurityService;
import ai.fabric.privacy.pii.PIIDetectionService;
import ai.fabric.access.AIAccessControlService;
import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.search.VectorSearchService;
import ai.fabric.embedding.EmbeddingProvider;
import ai.fabric.vector.VectorDatabase;
import ai.fabric.vector.VectorDatabaseServiceAdapter;
import ai.fabric.health.AIHealthIndicator;
import ai.fabric.health.VectorProviderHealthIndicator;
import ai.fabric.validation.AIProviderConfigValidator;
import ai.fabric.http.AIHttpClientFactory;
import ai.fabric.http.AIHttpClientProperties;
import ai.fabric.http.DefaultAIHttpClientFactory;
import ai.fabric.http.HttpClient;
import ai.fabric.intent.action.InMemoryPendingActionStore;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.invocation.DefaultGovernedActionInvocationService;
import ai.fabric.intent.action.invocation.GovernedActionInvocationService;
import ai.fabric.intent.action.tool.AIActionToolCallbackFactory;
import ai.fabric.intent.action.PendingActionStore;
import ai.fabric.intent.actiondraft.ActionDraftStore;
import ai.fabric.intent.actiondraft.InMemoryActionDraftStore;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Conditional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationPackage;
import org.springframework.boot.autoconfigure.AutoConfigureBefore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.ai.tool.ToolCallback;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.fabric.config.condition.EmbeddingsFeatureEnabledCondition;
import ai.fabric.config.condition.SearchFeatureEnabledCondition;
import ai.fabric.config.condition.VectorDbConfiguredCondition;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import jakarta.persistence.EntityManagerFactory;

/**
 * Auto-configuration for AI Infrastructure module
 * 
 * This class automatically configures all AI infrastructure beans when the module
 * is included in a Spring Boot application.
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@Slf4j
@AutoConfiguration
@AutoConfigurationPackage(basePackages = {
    "ai.fabric.entity",
    "ai.fabric.repository"
})
@AutoConfigureBefore({
    org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration.class
})
@EnableConfigurationProperties({
    AIProviderConfig.class,
    AIServiceConfig.class,
    VectorDatabaseConfig.class,
    OrchestrationProperties.class,
    AttachmentsProperties.class,
    PromptBundleProperties.class,
    ProgressiveIntentExtractionProperties.class,
    RelationshipQueryPostActionGenerationProperties.class,
    PostActionGenerationProperties.class,
    VectorSpaceRoutingProperties.class,
    SmartSuggestionsProperties.class,
    ResponseSanitizationProperties.class,
    OrchestrationResultNormalizationProperties.class,
    IntentHistoryProperties.class,
    SecurityProperties.class,
    AIHttpClientProperties.class
    // Connector-backed actions are enabled via the optional ai-infrastructure-actions-connector module.
})
@ComponentScan(
    basePackages = "ai.fabric",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = {
            "ai\\.fabric\\.behavior\\..*",
            "ai\\.fabric\\.chat\\..*",
            "ai\\.fabric\\.execution\\..*",
            "ai\\.fabric\\.rag\\..*",
            "ai\\.fabric\\.relationship\\..*",
            "ai\\.fabric\\.web\\..*",
            "ai\\.fabric\\.migration\\..*",
            "ai\\.fabric\\.it\\..*",
            "ai\\.fabric\\.onnxstarter\\..*",
            "ai\\.fabric\\.config\\..*"
        }
    )
)
@ConditionalOnProperty(prefix = "ai", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AIInfrastructureAutoConfiguration {
    
    public AIInfrastructureAutoConfiguration() {
        log.debug("AIInfrastructureAutoConfiguration instance created");
    }
    
    // EmbeddingProvider beans - selected based on configuration
    // Embedding provider implementations are supplied by dedicated modules

    @Bean
    @ConditionalOnMissingBean(AIEmbeddingService.class)
    @ConditionalOnProperty(prefix = "ai.service.features", name = "enable-embeddings", havingValue = "true", matchIfMissing = true)
    public AIEmbeddingService aiEmbeddingService(AIProviderConfig config,
                                                 List<EmbeddingProvider> embeddingProviders,
                                                 ObjectProvider<CacheManager> cacheManagerProvider,
                                                 @Qualifier("onnxFallbackEmbeddingProvider") ObjectProvider<EmbeddingProvider> fallbackProvider) {
        String requestedProvider = config.getEmbeddingProvider() != null
            ? config.getEmbeddingProvider().toLowerCase()
            : null;

        EmbeddingProvider selectedProvider = embeddingProviders.stream()
            .filter(provider -> provider != null && provider.getProviderName() != null)
            .filter(provider -> requestedProvider == null
                || provider.getProviderName().equalsIgnoreCase(requestedProvider))
            .findFirst()
            .orElseGet(() -> embeddingProviders.isEmpty() ? null : embeddingProviders.get(0));

        String providerName = selectedProvider != null ? selectedProvider.getProviderName() : "null";
        log.info("Creating AIEmbeddingService with embedding provider '{}' (requested '{}')",
            providerName,
            requestedProvider != null ? requestedProvider : "unspecified");

        if (selectedProvider == null) {
            log.warn("No embedding provider matched request '{}'. Available providers: {}",
                requestedProvider,
                embeddingProviders.stream()
                    .filter(Objects::nonNull)
                    .map(EmbeddingProvider::getProviderName)
                    .collect(Collectors.joining(", ")));
        }
        
        CacheManager cacheManager = cacheManagerProvider.getIfUnique(NoOpCacheManager::new);
        EmbeddingProvider fallbackEmbeddingProvider = fallbackProvider != null ? fallbackProvider.getIfAvailable() : null;
        return new AIEmbeddingService(config, selectedProvider, cacheManager, fallbackEmbeddingProvider);
    }
    
    @Bean
    @ConditionalOnMissingBean(AISearchService.class)
    @Conditional({VectorDbConfiguredCondition.class, SearchFeatureEnabledCondition.class})
    public AISearchService aiSearchService(AIProviderConfig config,
                                           VectorSearchService vectorSearchService,
                                           VectorManagementService vectorManagementService) {
        return new AISearchService(config, vectorSearchService, vectorManagementService);
    }

    @Bean
    @ConditionalOnMissingBean(Pipeline.class)
    public Pipeline orchestrationPipeline(List<PipelineStep> steps) {
        return new DefaultOrchestrationPipeline(steps);
    }
    
    // AdvancedRAGService is now provided by ai-infrastructure-rag module
    // See ai.fabric.rag.config.RAGAutoConfiguration
    
    @Bean
    public AISecurityService aiSecurityService(ObjectProvider<PIIDetectionService> piiDetectionService,
                                               Clock clock,
                                               SecurityProperties securityProperties) {
        return new AISecurityService(piiDetectionService.getIfAvailable(), clock, securityProperties);
    }
    
    @Bean
    public AIAccessControlService aiAccessControlService(Clock clock,
                                                         ObjectProvider<EntityAccessPolicy> entityAccessPolicyProvider,
                                                         ObjectProvider<CacheManager> cacheManagerProvider) {
        return new AIAccessControlService(
            clock,
            entityAccessPolicyProvider.getIfAvailable(),
            cacheManagerProvider.getIfAvailable()
        );
    }
    
    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock systemClock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean(ObjectMapper.class)
    public ObjectMapper aiFabricObjectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }

    @Bean
    @ConditionalOnClass(ToolCallback.class)
    @ConditionalOnMissingBean
    public AIActionToolCallbackFactory aiActionToolCallbackFactory(ObjectProvider<AIActionRegistry> actionRegistry,
                                                                  ObjectMapper objectMapper) {
        return new AIActionToolCallbackFactory(actionRegistry::getIfAvailable, objectMapper);
    }

    @Bean
    @ConditionalOnMissingBean
    public EffectiveCapabilitiesResolver effectiveCapabilitiesResolver() {
        return new DefaultEffectiveCapabilitiesResolver();
    }

    @Bean
    @ConditionalOnBean(AIActionRegistry.class)
    @ConditionalOnMissingBean
    public GovernedActionInvocationService governedActionInvocationService(
        AIActionRegistry actionRegistry
    ) {
        return new DefaultGovernedActionInvocationService(actionRegistry);
    }

    @Bean
    @ConditionalOnMissingBean(AIHttpClientFactory.class)
    public AIHttpClientFactory aiHttpClientFactory(RestTemplateBuilder restTemplateBuilder,
                                                   AIHttpClientProperties properties) {
        return new DefaultAIHttpClientFactory(restTemplateBuilder, properties);
    }

    @Bean
    @ConditionalOnMissingBean(HttpClient.class)
    public HttpClient aiHttpClient(AIHttpClientFactory factory) {
        return factory.create();
    }

    @Bean(name = "defaultPendingActionStore")
    @ConditionalOnMissingBean(PendingActionStore.class)
    public PendingActionStore defaultPendingActionStore() {
        return new InMemoryPendingActionStore();
    }

    @Bean(name = "defaultActionDraftStore")
    @ConditionalOnMissingBean(ActionDraftStore.class)
    public ActionDraftStore defaultActionDraftStore() {
        return new InMemoryActionDraftStore();
    }
    
    // Vector database implementations are provided by dedicated vector modules

    @Bean(name = "AIEntityConfigurationLoader")
    @ConditionalOnMissingBean(name = "AIEntityConfigurationLoader")
    public AIEntityConfigurationLoader aiEntityConfigurationLoader(Environment environment) {
        return new AIEntityConfigurationLoader(environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public AIEntityDescriptorRegistry aiEntityDescriptorRegistry(
        AIEntityConfigurationLoader entityConfigurationLoader,
        ObjectProvider<EntityIdentityResolver> identityResolvers,
        ObjectProvider<AIEntityDescriptorContributor> contributors,
        ObjectProvider<PIIDetectionService> piiDetectionService,
        ObjectMapper objectMapper
    ) {
        return new AIEntityDescriptorRegistry(
            entityConfigurationLoader,
            identityResolvers.orderedStream().toList(),
            contributors.orderedStream().toList(),
            piiDetectionService,
            objectMapper
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AIEntityProjectionService aiEntityProjectionService(
        AIEntityDescriptorRegistry descriptorRegistry,
        ObjectProvider<PIIDetectionService> piiDetectionService,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        return new AIEntityProjectionService(
            descriptorRegistry,
            piiDetectionService,
            objectMapper,
            clock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AIConfiguredEntityProjectionService aiConfiguredEntityProjectionService(
        ObjectProvider<PIIDetectionService> piiDetectionService,
        ObjectMapper objectMapper,
        Clock clock
    ) {
        return new AIConfiguredEntityProjectionService(
            piiDetectionService,
            objectMapper,
            clock
        );
    }

    @Bean
    @ConditionalOnMissingBean
    public AIEntityDescriptorInitializer aiEntityDescriptorInitializer(
        AIEntityDescriptorRegistry descriptorRegistry,
        ObjectProvider<EntityManagerFactory> entityManagerFactory
    ) {
        return new AIEntityDescriptorInitializer(descriptorRegistry, entityManagerFactory);
    }
    
    @Bean
    @ConditionalOnMissingBean
    @Conditional(VectorDbConfiguredCondition.class)
    public VectorManagementService vectorManagementService(VectorDatabaseService vectorDatabaseService,
                                                           AIEntityConfigurationLoader entityConfigurationLoader,
                                                           ObjectProvider<CacheManager> cacheManagerProvider) {
        CacheManager cacheManager = cacheManagerProvider.getIfUnique(NoOpCacheManager::new);
        return new VectorManagementService(vectorDatabaseService, entityConfigurationLoader, cacheManager);
    }
    @Bean
    public EmbeddingProcessor embeddingProcessor(AIProviderConfig config) {
        return new EmbeddingProcessor(config);
    }
    
    @Bean
    @Conditional({VectorDbConfiguredCondition.class, SearchFeatureEnabledCondition.class})
    public VectorSearchService vectorSearchService(AIProviderConfig config,
                                                  VectorDatabaseService vectorDatabaseService,
                                                  ObjectProvider<CacheManager> cacheManagerProvider) {
        CacheManager cacheManager = cacheManagerProvider.getIfUnique(NoOpCacheManager::new);
        return new VectorSearchService(config, vectorDatabaseService, cacheManager);
    }
    
    @Bean
    @ConditionalOnMissingBean(VectorDatabase.class)
    @Conditional(VectorDbConfiguredCondition.class)
    public VectorDatabase vectorDatabase(VectorDatabaseService vectorDatabaseService) {
        return new VectorDatabaseServiceAdapter(vectorDatabaseService);
    }
    
    @Bean
    public AIProviderConfigValidator aiProviderConfigValidator(AIProviderConfig providerConfig, AIServiceConfig serviceConfig) {
        return new AIProviderConfigValidator(providerConfig, serviceConfig);
    }

    @Bean
    @ConditionalOnMissingBean(ai.fabric.service.AIConfigurationService.class)
    public ai.fabric.service.AIConfigurationService aiServiceConfigurationService(
        AIProviderConfig providerConfig,
        AIServiceConfig serviceConfig
    ) {
        return new ai.fabric.service.AIConfigurationService(providerConfig, serviceConfig);
    }

    @Bean
    public AIHealthIndicator aiHealthIndicator(
        ai.fabric.service.AIConfigurationService configurationService,
        AIServiceConfig serviceConfig,
        AIProviderConfig providerConfig
    ) {
        return new AIHealthIndicator(configurationService, serviceConfig, providerConfig);
    }

    @Bean(name = "vectorProviderHealthIndicator")
    @ConditionalOnClass(HealthIndicator.class)
    @ConditionalOnBean(VectorManagementService.class)
    @ConditionalOnMissingBean(name = "vectorProviderHealthIndicator")
    @ConditionalOnProperty(prefix = "management.health.ai-fabric.vector", name = "enabled", havingValue = "true", matchIfMissing = true)
    public HealthIndicator vectorProviderHealthIndicator(VectorManagementService vectorManagementService) {
        return new VectorProviderHealthIndicator(vectorManagementService);
    }

    @Bean
    @ConditionalOnMissingBean
    public ai.fabric.service.AIInfrastructureProfileService aiInfrastructureProfileService(
        ai.fabric.repository.AIInfrastructureProfileRepository aiInfrastructureProfileRepository,
        ObjectProvider<AIEntityIndexingGateway> indexingGatewayProvider
    ) {
        return new ai.fabric.service.AIInfrastructureProfileService(
            aiInfrastructureProfileRepository,
            indexingGatewayProvider
        );
    }
}
