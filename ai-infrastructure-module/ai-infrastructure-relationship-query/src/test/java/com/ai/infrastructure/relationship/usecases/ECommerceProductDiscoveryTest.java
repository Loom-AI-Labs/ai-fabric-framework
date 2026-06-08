package com.ai.infrastructure.relationship.usecases;

import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.relationship.cache.QueryCache;
import com.ai.infrastructure.relationship.config.RelationshipModuleMetadata;
import com.ai.infrastructure.relationship.config.RelationshipQueryProperties;
import com.ai.infrastructure.relationship.dto.FilterCondition;
import com.ai.infrastructure.relationship.dto.FilterOperator;
import com.ai.infrastructure.relationship.dto.JpqlQuery;
import com.ai.infrastructure.relationship.dto.RelationshipDirection;
import com.ai.infrastructure.relationship.dto.RelationshipPath;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import com.ai.infrastructure.relationship.dto.QueryStrategy;
import com.ai.infrastructure.relationship.integration.IntegrationTestSupport;
import com.ai.infrastructure.relationship.integration.RelationshipQueryTestApplication;
import com.ai.infrastructure.relationship.integration.entity.BrandEntity;
import com.ai.infrastructure.relationship.integration.entity.ProductEntity;
import com.ai.infrastructure.relationship.integration.repository.BrandRepository;
import com.ai.infrastructure.relationship.integration.repository.ProductRepository;
import com.ai.infrastructure.relationship.metrics.QueryMetrics;
import com.ai.infrastructure.relationship.model.QueryOptions;
import com.ai.infrastructure.relationship.model.ReturnMode;
import com.ai.infrastructure.relationship.service.DefaultRelationshipQueryDocumentMapper;
import com.ai.infrastructure.relationship.service.DynamicJPAQueryBuilder;
import com.ai.infrastructure.relationship.service.LLMDrivenJPAQueryService;
import com.ai.infrastructure.relationship.service.RelationshipQueryPlanner;
import com.ai.infrastructure.relationship.service.RelationshipQueryDocumentMapper;
import com.ai.infrastructure.relationship.validation.RelationshipQueryValidator;
import com.ai.infrastructure.relationship.service.EntityRelationshipMapper;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = RelationshipQueryTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("integration")
@Import(ECommerceProductDiscoveryTest.VectorOverrides.class)
class ECommerceProductDiscoveryTest {

    private static final Logger log = LoggerFactory.getLogger(ECommerceProductDiscoveryTest.class);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerCommonProperties(registry);
    }

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private AIEntityConfigurationLoader configurationLoader;

    @Autowired
    private RelationshipQueryPlanner planner;

    @Autowired
    private DynamicJPAQueryBuilder dynamicJPAQueryBuilder;

    @Autowired
    private RelationshipQueryValidator relationshipQueryValidator;

    @Autowired
    private RelationshipQueryProperties relationshipQueryProperties;

    @Autowired
    private RelationshipModuleMetadata relationshipModuleMetadata;

    @Autowired
    private VectorDatabaseService vectorDatabaseService;

    @Autowired
    private AIEmbeddingService aiEmbeddingService;

    @Autowired
    private QueryCache queryCache;

    @Autowired
    private QueryMetrics queryMetrics;

    @Autowired
    private EntityRelationshipMapper entityRelationshipMapper;

    @PersistenceContext
    private EntityManager entityManager;

    private LLMDrivenJPAQueryService llmDrivenJPAQueryService;
    private String nikeBlueRunnerId;

    @BeforeEach
    void setUp() {
        Mockito.reset(planner);
        productRepository.deleteAll();
        brandRepository.deleteAll();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ex) {
                log.warn("Unable to clear vectors from Lucene test index; continuing with fresh context", ex);
            }
        }

        seedCatalog();

        var schemaProvider = new com.ai.infrastructure.relationship.service.RelationshipSchemaProvider(
            entityManager,
            null,
            relationshipQueryProperties,
            entityRelationshipMapper
        );
        schemaProvider.refreshSchema();

        var jpaTraversalService = new com.ai.infrastructure.relationship.service.JpaRelationshipTraversalService(entityManager);
        RelationshipQueryDocumentMapper documentMapper = new DefaultRelationshipQueryDocumentMapper(null, configurationLoader);

        llmDrivenJPAQueryService = new LLMDrivenJPAQueryService(
            planner,
            dynamicJPAQueryBuilder,
            relationshipQueryValidator,
            relationshipQueryProperties,
            relationshipModuleMetadata,
            jpaTraversalService,
            documentMapper,
            vectorDatabaseService,
            aiEmbeddingService,
            queryCache,
            queryMetrics
        );
    }

    @Test
    void shouldFindBlueNikeShoesUnderHundred() {
        String query = "Show me blue shoes under $100 from Nike";

        FilterCondition colorFilter = FilterCondition.builder()
            .field("color")
            .operator(FilterOperator.ILIKE)
            .value("blue")
            .build();

        FilterCondition priceFilter = FilterCondition.builder()
            .field("price")
            .operator(FilterOperator.LESS_THAN_OR_EQUAL)
            .value(BigDecimal.valueOf(100))
            .build();

        RelationshipPath brandPath = RelationshipPath.builder()
            .fromEntityType("product")
            .relationshipType("brand")
            .toEntityType("brand")
            .direction(RelationshipDirection.FORWARD)
            .optional(false)
            .conditions(List.of(FilterCondition.builder()
                .field("name")
                .operator(FilterOperator.ILIKE)
                .value("%Nike%")
                .build()))
            .build();

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery(query)
            .primaryEntityType("product")
            .candidateEntityTypes(List.of("product", "brand"))
            .relationshipPaths(List.of(brandPath))
            .directFilters(Map.of("product", List.of(colorFilter, priceFilter)))
            .queryStrategy(QueryStrategy.RELATIONSHIP)
            .returnMode(ReturnMode.FULL)
            .needsSemanticSearch(false)
            .limit(5)
            .build();

        when(planner.planQuery(eq(query), eq(List.of("product")))).thenReturn(plan);

        JpqlQuery jpqlQuery = dynamicJPAQueryBuilder.buildQuery(plan);
        log.info("[ECommerce] User query: {}", query);
        log.info("[ECommerce] Planner plan: {}", plan);
        log.info("[ECommerce] JPQL: {}", jpqlQuery.getJpql());

        QueryOptions options = QueryOptions.builder()
            .returnMode(ReturnMode.FULL)
            .limit(5)
            .build();

        RAGResponse response = llmDrivenJPAQueryService.executeRelationshipQuery(query, List.of("product"), options);

        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo(nikeBlueRunnerId);
        assertThat(response.getDocuments().get(0).getContent()).contains("Blue Runner");
        log.info("[ECommerce] Result documents: {}", response.getDocuments());
    }

    private void seedCatalog() {
        BrandEntity nike = new BrandEntity();
        nike.setName("Nike");

        BrandEntity adidas = new BrandEntity();
        adidas.setName("Adidas");

        brandRepository.save(nike);
        brandRepository.save(adidas);

        ProductEntity blueRunner = product("Blue Runner 2", "blue", BigDecimal.valueOf(85), "ACTIVE", nike);
        ProductEntity premiumBoot = product("Blue Trail Boot", "blue", BigDecimal.valueOf(180), "ACTIVE", nike);
        ProductEntity redRunner = product("Red Runner", "red", BigDecimal.valueOf(90), "ACTIVE", nike);
        ProductEntity adidasBlue = product("City Flex", "blue", BigDecimal.valueOf(95), "ACTIVE", adidas);

        nikeBlueRunnerId = blueRunner.getId();

        entityRelationshipMapper.registerEntityType(ProductEntity.class);
        entityRelationshipMapper.registerEntityType(BrandEntity.class);
        try {
            entityRelationshipMapper.registerRelationship("product", "brand", "brand", RelationshipDirection.FORWARD, false);
        } catch (IllegalStateException ignored) { }
    }

    private ProductEntity product(String name, String color, BigDecimal price, String status, BrandEntity brand) {
        ProductEntity product = new ProductEntity();
        product.setName(name);
        product.setColor(color);
        product.setPrice(price);
        product.setStatus(status);
        product.setBrand(brand);
        brand.getProducts().add(product);
        return productRepository.save(product);
    }

    @TestConfiguration
    static class VectorOverrides {

        @Bean
        public VectorDatabaseService vectorDatabaseService() {
            return Mockito.mock(VectorDatabaseService.class);
        }
    }

}
