package com.ai.infrastructure.config;

import com.ai.infrastructure.service.AICapabilityService;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.core.AISearchService;
import com.ai.infrastructure.intent.orchestration.pipeline.DefaultOrchestrationPipeline;
import com.ai.infrastructure.intent.orchestration.pipeline.Pipeline;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineStep;
import com.ai.infrastructure.processor.AICapableProcessor;
import com.ai.infrastructure.processor.AnnotationFieldScanner;
import com.ai.infrastructure.processor.EmbeddingProcessor;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.service.VectorManagementService;
import com.ai.infrastructure.security.AISecurityService;
import com.ai.infrastructure.privacy.pii.PIIDetectionService;
import com.ai.infrastructure.access.AIAccessControlService;
import com.ai.infrastructure.access.policy.EntityAccessPolicy;
import com.ai.infrastructure.search.VectorSearchService;
import com.ai.infrastructure.embedding.EmbeddingProvider;
import com.ai.infrastructure.vector.VectorDatabase;
import com.ai.infrastructure.vector.VectorDatabaseServiceAdapter;
import com.ai.infrastructure.health.AIHealthIndicator;
import com.ai.infrastructure.validation.AIProviderConfigValidator;
import com.ai.infrastructure.http.AIHttpClientFactory;
import com.ai.infrastructure.http.AIHttpClientProperties;
import com.ai.infrastructure.http.DefaultAIHttpClientFactory;
import com.ai.infrastructure.http.HttpClient;
import com.ai.infrastructure.intent.action.InMemoryPendingActionStore;
import com.ai.infrastructure.intent.action.PendingActionStore;
import com.ai.infrastructure.intent.actiondraft.ActionDraftStore;
import com.ai.infrastructure.intent.actiondraft.InMemoryActionDraftStore;
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
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cache.CacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ResourceLoader;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ai.infrastructure.config.condition.EmbeddingsFeatureEnabledCondition;
import com.ai.infrastructure.config.condition.SearchFeatureEnabledCondition;
import com.ai.infrastructure.config.condition.VectorDbConfiguredCondition;

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
    "com.ai.infrastructure.entity",
    "com.ai.infrastructure.repository"
})
@AutoConfigureBefore({
    org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration.class,
    org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration.class
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
    basePackages = "com.ai.infrastructure",
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = {
            "com\\.ai\\.infrastructure\\.behavior\\..*",
            "com\\.ai\\.infrastructure\\.chat\\..*",
            "com\\.ai\\.infrastructure\\.rag\\..*",
            "com\\.ai\\.infrastructure\\.relationship\\..*",
            "com\\.ai\\.infrastructure\\.web\\..*",
            "com\\.ai\\.infrastructure\\.migration\\..*",
            "com\\.ai\\.infrastructure\\.it\\..*",
            "com\\.ai\\.infrastructure\\.onnxstarter\\..*",
            "com\\.ai\\.infrastructure\\.config\\..*"
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
    // See com.ai.infrastructure.rag.config.RAGAutoConfiguration
    
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
    public AIEntityConfigurationLoader aiEntityConfigurationLoader(ResourceLoader resourceLoader) {
        return new AIEntityConfigurationLoader(resourceLoader);
    }

    @Bean
    @ConditionalOnClass(EntityManagerFactory.class)
    @ConditionalOnBean(EntityManagerFactory.class)
    @ConditionalOnProperty(prefix = "ai.config.annotation-metadata", name = "enabled", havingValue = "true", matchIfMissing = true)
    public AnnotationMetadataEntityConfigRegistrar annotationMetadataEntityConfigRegistrar(
        EntityManagerFactory entityManagerFactory,
        AIEntityConfigurationLoader entityConfigurationLoader,
        AnnotationFieldScanner annotationFieldScanner,
        @Value("${ai.config.annotation-metadata.create-missing-entity-config:false}") boolean createMissingEntityConfig
    ) {
        return new AnnotationMetadataEntityConfigRegistrar(
            entityManagerFactory,
            entityConfigurationLoader,
            annotationFieldScanner,
            createMissingEntityConfig
        );
    }
    
    @Bean
    @ConditionalOnMissingBean
    @Conditional(VectorDbConfiguredCondition.class)
    public VectorManagementService vectorManagementService(VectorDatabaseService vectorDatabaseService,
                                                           AIEntityConfigurationLoader entityConfigurationLoader) {
        return new VectorManagementService(vectorDatabaseService, entityConfigurationLoader);
    }
    
	    @Bean
	    @ConditionalOnMissingBean
        @Conditional({VectorDbConfiguredCondition.class, EmbeddingsFeatureEnabledCondition.class})
	    public AICapabilityService aiCapabilityService(
	            AIEmbeddingService embeddingService,
            AICoreService aiCoreService,
            AIEntityConfigurationLoader entityConfigurationLoader,
            VectorManagementService vectorManagementService,
            AnnotationFieldScanner annotationFieldScanner,
            PromptTemplateResolver promptTemplateResolver,
            PromptRenderer promptRenderer) {
        return new AICapabilityService(
            embeddingService,
            aiCoreService,
            entityConfigurationLoader,
            vectorManagementService,
            annotationFieldScanner,
            promptTemplateResolver,
            promptRenderer
        );
    }
    
    @Bean
    @ConditionalOnMissingBean
    public AICapableProcessor aiCapableProcessor(AnnotationFieldScanner annotationFieldScanner) {
        return new AICapableProcessor(annotationFieldScanner);
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
    @ConditionalOnMissingBean(com.ai.infrastructure.service.AIConfigurationService.class)
    public com.ai.infrastructure.service.AIConfigurationService aiServiceConfigurationService(
        AIProviderConfig providerConfig,
        AIServiceConfig serviceConfig
    ) {
        return new com.ai.infrastructure.service.AIConfigurationService(providerConfig, serviceConfig);
    }

    @Bean
    public AIHealthIndicator aiHealthIndicator(
        com.ai.infrastructure.service.AIConfigurationService configurationService,
        AIServiceConfig serviceConfig,
        AIProviderConfig providerConfig
    ) {
        return new AIHealthIndicator(configurationService, serviceConfig, providerConfig);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AICapabilityService.class)
    public com.ai.infrastructure.service.AIInfrastructureProfileService aiInfrastructureProfileService(com.ai.infrastructure.repository.AIInfrastructureProfileRepository aiInfrastructureProfileRepository, AICapabilityService aiCapabilityService) {
        return new com.ai.infrastructure.service.AIInfrastructureProfileService(aiInfrastructureProfileRepository, aiCapabilityService);
    }
}
