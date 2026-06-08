package com.ai.infrastructure.relationship.config;

import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.processor.AnnotationFieldScanner;
import com.ai.infrastructure.relationship.cache.QueryCache;
import com.ai.infrastructure.relationship.metrics.QueryMetrics;
import com.ai.infrastructure.relationship.action.RelationshipQueryActionHandler;
import com.ai.infrastructure.relationship.service.DefaultRelationshipQueryDocumentMapper;
import com.ai.infrastructure.relationship.service.DynamicJPAQueryBuilder;
import com.ai.infrastructure.relationship.service.EntityRelationshipMapper;
import com.ai.infrastructure.relationship.service.JpaRelationshipTraversalService;
import com.ai.infrastructure.relationship.service.LLMDrivenJPAQueryService;
import com.ai.infrastructure.relationship.service.RelationshipQueryDocumentMapper;
import com.ai.infrastructure.relationship.service.RelationshipQueryPlanner;
import com.ai.infrastructure.relationship.service.RelationshipSchemaProvider;
import com.ai.infrastructure.relationship.service.RelationshipTraversalService;
import com.ai.infrastructure.relationship.service.ReliableRelationshipQueryService;
import com.ai.infrastructure.relationship.spi.RelationshipQueryAccessControlPolicy;
import com.ai.infrastructure.relationship.validation.RelationshipQueryValidator;
import com.ai.infrastructure.llm.structured.StructuredJsonExtractor;
import com.ai.infrastructure.prompt.PromptRenderer;
import com.ai.infrastructure.prompt.PromptTemplateResolver;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.Nullable;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.orm.jpa.SharedEntityManagerCreator;

/**
 * Base configuration that exposes shared beans for the relationship query module.
 */
@Configuration(proxyBeanMethods = false)
class RelationshipQueryConfiguration {

    @Bean
    @ConditionalOnMissingBean
    QueryCache relationshipQueryCache(RelationshipQueryProperties properties) {
        return new QueryCache(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    QueryMetrics relationshipQueryMetrics(RelationshipQueryProperties properties) {
        return new QueryMetrics(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    RelationshipModuleMetadata relationshipModuleMetadata(RelationshipQueryProperties properties) {
        return RelationshipModuleMetadata.from(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    EntityRelationshipMapper entityRelationshipMapper() {
        return new EntityRelationshipMapper();
    }

    @Bean
    @ConditionalOnSingleCandidate(EntityManagerFactory.class)
    @ConditionalOnMissingBean
    RelationshipSchemaProvider relationshipSchemaProvider(EntityManagerFactory entityManagerFactory,
                                                         @Nullable AIEntityConfigurationLoader configurationLoader,
                                                         RelationshipQueryProperties properties,
                                                         EntityRelationshipMapper mapper) {
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        return new RelationshipSchemaProvider(entityManager, configurationLoader, properties, mapper);
    }

    @Bean
    @ConditionalOnMissingBean
    RelationshipQueryValidator relationshipQueryValidator(EntityRelationshipMapper mapper) {
        return new RelationshipQueryValidator(mapper);
    }

    @Bean
    @ConditionalOnBean({RelationshipSchemaProvider.class, AICoreService.class})
    @ConditionalOnMissingBean
    RelationshipQueryPlanner relationshipQueryPlanner(AICoreService aiCoreService,
                                                      RelationshipSchemaProvider schemaProvider,
                                                      RelationshipQueryProperties properties,
                                                      RelationshipQueryValidator validator,
                                                      QueryCache queryCache,
                                                      QueryMetrics queryMetrics,
                                                      ObjectMapper objectMapper,
                                                      StructuredJsonExtractor structuredJsonExtractor,
                                                      PromptTemplateResolver promptTemplateResolver,
                                                      PromptRenderer promptRenderer) {
        return new RelationshipQueryPlanner(
            aiCoreService,
            schemaProvider,
            properties,
            validator,
            queryCache,
            queryMetrics,
            objectMapper,
            structuredJsonExtractor,
            promptTemplateResolver,
            promptRenderer
        );
    }

    @Bean
    @ConditionalOnMissingBean
    DynamicJPAQueryBuilder dynamicJPAQueryBuilder(EntityRelationshipMapper mapper) {
        return new DynamicJPAQueryBuilder(mapper);
    }

    @Bean(name = "jpaRelationshipTraversalService")
    @ConditionalOnSingleCandidate(EntityManagerFactory.class)
    @ConditionalOnMissingBean(name = "jpaRelationshipTraversalService")
    RelationshipTraversalService jpaRelationshipTraversalService(EntityManagerFactory entityManagerFactory) {
        EntityManager entityManager = SharedEntityManagerCreator.createSharedEntityManager(entityManagerFactory);
        return new JpaRelationshipTraversalService(entityManager);
    }

    @Bean
    @ConditionalOnMissingBean
    RelationshipQueryDocumentMapper relationshipQueryDocumentMapper(@Nullable AnnotationFieldScanner annotationFieldScanner,
                                                                    @Nullable AIEntityConfigurationLoader configurationLoader) {
        return new DefaultRelationshipQueryDocumentMapper(annotationFieldScanner, configurationLoader);
    }

    @Bean
    @ConditionalOnBean(
        value = {
            RelationshipQueryPlanner.class,
            DynamicJPAQueryBuilder.class,
            RelationshipQueryValidator.class,
            RelationshipModuleMetadata.class
        },
        name = {"jpaRelationshipTraversalService"}
    )
    @ConditionalOnMissingBean
    LLMDrivenJPAQueryService relationshipQueryService(RelationshipQueryPlanner planner,
                                                      DynamicJPAQueryBuilder queryBuilder,
                                                      RelationshipQueryValidator validator,
                                                      RelationshipQueryProperties properties,
                                                      RelationshipModuleMetadata metadata,
                                                      @Qualifier("jpaRelationshipTraversalService") RelationshipTraversalService jpaRelationshipTraversalService,
                                                      RelationshipQueryDocumentMapper documentMapper,
                                                      @Nullable VectorDatabaseService vectorDatabaseService,
                                                      @Nullable AIEmbeddingService embeddingService,
                                                      QueryCache queryCache,
                                                      QueryMetrics queryMetrics) {
        return new LLMDrivenJPAQueryService(
            planner,
            queryBuilder,
            validator,
            properties,
            metadata,
            jpaRelationshipTraversalService,
            documentMapper,
            vectorDatabaseService,
            embeddingService,
            queryCache,
            queryMetrics
        );
    }

    @Bean
    @ConditionalOnMissingBean
    RelationshipModuleMarker relationshipModuleMarker() {
        return new RelationshipModuleMarker();
    }

    @Bean
    @ConditionalOnBean(
        value = {
            LLMDrivenJPAQueryService.class
        }
    )
    @ConditionalOnMissingBean
    ReliableRelationshipQueryService reliableRelationshipQueryService(LLMDrivenJPAQueryService llmDrivenJPAQueryService) {
        return new ReliableRelationshipQueryService(llmDrivenJPAQueryService);
    }

    @Bean
    @ConditionalOnBean({ReliableRelationshipQueryService.class, RelationshipQueryAccessControlPolicy.class})
    @ConditionalOnProperty(
        prefix = "ai.infrastructure.relationship",
        name = "enable-orchestrator-integration",
        havingValue = "true",
        matchIfMissing = true
    )
    @ConditionalOnMissingBean
    RelationshipQueryActionHandler relationshipQueryActionHandler(ReliableRelationshipQueryService queryService,
                                                                  RelationshipQueryAccessControlPolicy accessControlPolicy) {
        return new RelationshipQueryActionHandler(queryService, accessControlPolicy);
    }

    /**
     * Simple marker bean that allows downstream applications to confirm the module is active.
     */
    static final class RelationshipModuleMarker { }
}
