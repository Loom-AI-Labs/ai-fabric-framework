package com.ai.infrastructure.rag.config;

import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.config.AIInfrastructureAutoConfiguration;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.core.AISearchService;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.rag.service.AdvancedRAGService;
import com.ai.infrastructure.rag.service.RAGService;
import com.ai.infrastructure.rag.source.SearchSourceRegistry;
import com.ai.infrastructure.spi.RAGProvider;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.vector.VectorDatabase;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;

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
            ObjectProvider<SearchSourceRegistry> searchSourceRegistryProvider) {
        
        log.info(LOG_RAG_SERVICE_CREATED);
        
        return new RAGService(
            config,
            embeddingService,
            vectorDatabaseService,
            vectorDatabase,
            searchService,
            searchSourceRegistryProvider.getIfAvailable()
        );
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
            PromptRenderer promptRenderer) {
        
        log.info(LOG_ADVANCED_RAG_CREATED);
        
        return new AdvancedRAGService(
            searchService,
            embeddingService,
            coreService,
            ragProvider,
            promptTemplateResolver,
            promptRenderer
        );
    }
}
