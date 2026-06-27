package ai.fabric.relationship.config;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.core.AICoreService;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.processor.AnnotationFieldScanner;
import ai.fabric.relationship.cache.QueryCache;
import ai.fabric.relationship.metrics.QueryMetrics;
import ai.fabric.relationship.action.RelationshipQueryActionHandler;
import ai.fabric.relationship.service.DefaultRelationshipQueryDocumentMapper;
import ai.fabric.relationship.service.DynamicJPAQueryBuilder;
import ai.fabric.relationship.service.EntityRelationshipMapper;
import ai.fabric.relationship.service.JpaRelationshipTraversalService;
import ai.fabric.relationship.service.LLMDrivenJPAQueryService;
import ai.fabric.relationship.service.RelationshipQueryDocumentMapper;
import ai.fabric.relationship.service.RelationshipQueryPlanner;
import ai.fabric.relationship.service.RelationshipSchemaProvider;
import ai.fabric.relationship.service.RelationshipTraversalService;
import ai.fabric.relationship.service.ReliableRelationshipQueryService;
import ai.fabric.relationship.spi.RelationshipQueryAccessControlPolicy;
import ai.fabric.relationship.validation.RelationshipQueryValidator;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import ai.fabric.llm.structured.StructuredJsonExtractor;
import ai.fabric.prompt.PromptRenderer;
import ai.fabric.prompt.PromptTemplateResolver;
import ai.fabric.rag.VectorDatabaseService;
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
                                                      StructuredJsonCallExecutor structuredJsonCallExecutor,
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
            structuredJsonCallExecutor,
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
    static final class RelationshipModuleMarker {
        String moduleName() {
            return "relationship-query";
        }
    }
}
