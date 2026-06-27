package ai.fabric.rag.config;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.AIInfrastructureAutoConfiguration;
import ai.fabric.core.AICoreService;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.evaluation.springai.SpringAiRagEvaluationService;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.rag.service.RAGService;
import ai.fabric.rag.source.SearchSourceRegistry;
import ai.fabric.spi.RAGProvider;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.vector.VectorDatabase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.evaluation.FactCheckingEvaluator;
import org.springframework.ai.chat.evaluation.RelevancyEvaluator;
import org.springframework.ai.evaluation.Evaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Auto-configuration for RAG (Retrieval-Augmented Generation) module.
 * 
 * <p>This configuration provides:</p>
 * <ul>
 *   <li>{@link RAGService} - Default implementation of {@link RAGProvider} SPI</li>
 *   <li>{@link AdvancedRAGService} - Advanced RAG with query expansion and re-ranking</li>
 * </ul>
 * 
 * <p><strong>Required Dependencies:</strong></p>
 * <ul>
 *   <li>{@link AIProviderConfig} - AI provider configuration</li>
 *   <li>{@link AIEmbeddingService} - Embedding generation service</li>
 *   <li>{@link VectorDatabaseService} - Vector database operations</li>
 *   <li>{@link VectorDatabase} - Vector database interface</li>
 *   <li>{@link AISearchService} - Search service</li>
 * </ul>
 * 
 * <p><strong>Conditional Loading:</strong></p>
 * <ul>
 *   <li>RAGService loads when {@code ai.infrastructure.rag.enabled=true} (default)</li>
 *   <li>AdvancedRAGService loads when {@code ai.infrastructure.rag.advanced.enabled=true} (default)</li>
 *   <li>Custom RAGProvider implementations take precedence over default</li>
 * </ul>
 * 
 * <p><strong>Configuration Properties:</strong></p>
 * <pre>{@code
 * ai.infrastructure.rag:
 *   enabled: true                   # Enable RAG module
 *   default-limit: 10               # Default search result limit
 *   default-threshold: 0.7          # Default similarity threshold
 *   advanced:
 *     enabled: true                 # Enable advanced RAG features
 * }</pre>
 * 
 * @author AI Infrastructure Team
 * @version 1.0.0
 * @see RAGProvider
 * @see RAGService
 * @see AdvancedRAGService
 * @see RAGProperties
 * @since 1.0
 */
@Slf4j
@AutoConfiguration
@AutoConfigureAfter(AIInfrastructureAutoConfiguration.class)
@EnableConfigurationProperties(RAGProperties.class)
@ConditionalOnProperty(prefix = "ai.infrastructure.rag", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RAGAutoConfiguration {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String LOG_RAG_SERVICE_CREATED = "RAGService created as default RAGProvider implementation";
    private static final String LOG_ADVANCED_RAG_CREATED = "AdvancedRAGService created with RAGProvider integration";
    private static final String ADVANCED_RAG_SEARCH_EXECUTOR = "advancedRagSearchExecutor";
    
    // =========================================================================
    // Bean Definitions
    // =========================================================================
    
    /**
     * Creates the default RAGService bean implementing RAGProvider SPI.
     * 
     * <p>This bean is only created if:</p>
     * <ul>
     *   <li>No other RAGProvider bean exists</li>
     *   <li>All required dependencies are available</li>
     *   <li>RAG module is enabled</li>
     * </ul>
     * 
     * @param config AI provider configuration
     * @param embeddingService embedding generation service
     * @param vectorDatabaseService vector database operations
     * @param vectorDatabase vector database interface
     * @param searchService search service
     * @return RAGService implementing RAGProvider
     */
    @Bean("ragService")
    @ConditionalOnMissingBean(RAGProvider.class)
    @ConditionalOnBean({
        AIProviderConfig.class,
        AIEmbeddingService.class,
        VectorDatabaseService.class,
        VectorDatabase.class,
        AISearchService.class
    })
    public RAGService ragService(
            AIProviderConfig config,
            AIEmbeddingService embeddingService,
            VectorDatabaseService vectorDatabaseService,
            VectorDatabase vectorDatabase,
            AISearchService searchService,
            RAGProperties properties,
            ObjectProvider<SearchSourceRegistry> searchSourceRegistryProvider) {
        
        log.info(LOG_RAG_SERVICE_CREATED);
        
        return new RAGService(
            config,
            embeddingService,
            vectorDatabaseService,
            vectorDatabase,
            searchService,
            searchSourceRegistryProvider.getIfAvailable(),
            properties
        );
    }

    /**
     * Bounded executor for advanced RAG expanded-query fan-out.
     */
    @Bean(name = ADVANCED_RAG_SEARCH_EXECUTOR, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = ADVANCED_RAG_SEARCH_EXECUTOR)
    @ConditionalOnProperty(prefix = "ai.infrastructure.rag.advanced", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ExecutorService advancedRagSearchExecutor(RAGProperties properties) {
        int parallelism = properties != null && properties.getAdvanced() != null
            ? properties.getAdvanced().getMaxParallelSearches()
            : 4;
        int safeParallelism = Math.max(1, Math.min(64, parallelism));
        log.info("Advanced RAG search executor created with maxParallelSearches={}", safeParallelism);
        return Executors.newFixedThreadPool(safeParallelism, namedThreadFactory("ai-fabric-advanced-rag-search-"));
    }
    
    /**
     * Creates the AdvancedRAGService bean for advanced RAG operations.
     * 
     * <p>This bean is only created if:</p>
     * <ul>
     *   <li>Advanced RAG is enabled ({@code ai.infrastructure.rag.advanced.enabled=true})</li>
     *   <li>RAGProvider bean exists</li>
     *   <li>All required dependencies are available</li>
     * </ul>
     * 
     * @param searchService AI search service
     * @param embeddingService AI embedding service
     * @param coreService AI core service
     * @param ragProvider RAG provider (either RAGService or custom implementation)
     * @return AdvancedRAGService
     */
    @Bean
    @ConditionalOnProperty(prefix = "ai.infrastructure.rag.advanced", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean({
        AISearchService.class,
        AIEmbeddingService.class,
        AICoreService.class,
        RAGProvider.class
    })
    public AdvancedRAGService advancedRAGService(
            AISearchService searchService,
            AIEmbeddingService embeddingService,
            AICoreService coreService,
            RAGProvider ragProvider,
            PromptTemplateResolver promptTemplateResolver,
            PromptRenderer promptRenderer,
            RAGProperties properties,
            @Qualifier(ADVANCED_RAG_SEARCH_EXECUTOR) Executor advancedRagSearchExecutor) {
        
        log.info(LOG_ADVANCED_RAG_CREATED);
        
        return new AdvancedRAGService(
            searchService,
            embeddingService,
            coreService,
            ragProvider,
            promptTemplateResolver,
            promptRenderer,
            properties,
            advancedRagSearchExecutor
        );
    }

    private ThreadFactory namedThreadFactory(String prefix) {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = {
        "org.springframework.ai.chat.client.ChatClient$Builder",
        "org.springframework.ai.chat.evaluation.RelevancyEvaluator",
        "org.springframework.ai.chat.evaluation.FactCheckingEvaluator"
    })
    @ConditionalOnBean(ChatClient.Builder.class)
    @ConditionalOnProperty(
        prefix = "ai.infrastructure.rag.evaluation",
        name = "enabled",
        havingValue = "true"
    )
    static class SpringAiRagEvaluationConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "springAiRagRelevancyEvaluator")
        Evaluator springAiRagRelevancyEvaluator(ChatClient.Builder chatClientBuilder) {
            return RelevancyEvaluator.builder()
                .chatClientBuilder(chatClientBuilder)
                .build();
        }

        @Bean
        @ConditionalOnMissingBean(name = "springAiRagFactCheckingEvaluator")
        Evaluator springAiRagFactCheckingEvaluator(ChatClient.Builder chatClientBuilder) {
            return FactCheckingEvaluator.builder(chatClientBuilder).build();
        }

        @Bean
        @ConditionalOnMissingBean
        SpringAiRagEvaluationService springAiRagEvaluationService(
            @Qualifier("springAiRagRelevancyEvaluator") Evaluator relevancyEvaluator,
            @Qualifier("springAiRagFactCheckingEvaluator") ObjectProvider<Evaluator> factCheckingEvaluator
        ) {
            return new SpringAiRagEvaluationService(
                relevancyEvaluator,
                factCheckingEvaluator.getIfAvailable()
            );
        }
    }
}
