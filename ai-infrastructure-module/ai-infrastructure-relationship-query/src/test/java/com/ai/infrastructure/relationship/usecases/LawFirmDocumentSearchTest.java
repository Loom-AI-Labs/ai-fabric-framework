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
import com.ai.infrastructure.relationship.integration.entity.DocumentEntity;
import com.ai.infrastructure.relationship.integration.entity.UserEntity;
import com.ai.infrastructure.relationship.integration.repository.DocumentRepository;
import com.ai.infrastructure.relationship.integration.repository.UserRepository;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
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
@Import(LawFirmDocumentSearchTest.UseCaseVectorOverrides.class)
class LawFirmDocumentSearchTest {

    private static final Logger log = LoggerFactory.getLogger(LawFirmDocumentSearchTest.class);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerCommonProperties(registry);
        registry.add("spring.main.allow-bean-definition-overriding", () -> "true");
    }

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

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
    private String q4ContractId;

    @BeforeEach
    void setUp() {
        Mockito.reset(planner);
        documentRepository.deleteAll();
        userRepository.deleteAll();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ex) {
                log.warn("Unable to clear vectors from Lucene test index; continuing with fresh context", ex);
            }
        }

        seedLawFirmData();

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
    void shouldFindQ4ContractsForJohnSmith() {
        String query = "Find all contracts related to John Smith in Q4 2023";

        FilterCondition statusFilter = FilterCondition.builder()
            .field("status")
            .operator(FilterOperator.EQUALS)
            .value("ACTIVE")
            .build();

        FilterCondition quarterFilter = FilterCondition.builder()
            .field("title")
            .operator(FilterOperator.ILIKE)
            .value("%Q4 2023%")
            .build();

        RelationshipPath authorPath = RelationshipPath.builder()
            .fromEntityType("document")
            .relationshipType("author")
            .toEntityType("user")
            .direction(RelationshipDirection.FORWARD)
            .optional(false)
            .conditions(List.of(FilterCondition.builder()
                .field("fullName")
                .operator(FilterOperator.ILIKE)
                .value("%John Smith%")
                .build()))
            .build();

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery(query)
            .primaryEntityType("document")
            .candidateEntityTypes(List.of("document", "user"))
            .relationshipPaths(List.of(authorPath))
            .directFilters(Map.of("document", List.of(statusFilter, quarterFilter)))
            .queryStrategy(QueryStrategy.RELATIONSHIP)
            .returnMode(ReturnMode.FULL)
            .needsSemanticSearch(false)
            .limit(5)
            .build();

        when(planner.planQuery(eq(query), eq(List.of("document")))).thenReturn(plan);

        JpqlQuery jpqlQuery = dynamicJPAQueryBuilder.buildQuery(plan);
        log.info("[LawFirm] User query: {}", query);
        log.info("[LawFirm] Planner plan: {}", plan);
        log.info("[LawFirm] JPQL: {}", jpqlQuery.getJpql());

        QueryOptions options = QueryOptions.builder()
            .returnMode(ReturnMode.FULL)
            .limit(5)
            .build();

        RAGResponse response = llmDrivenJPAQueryService.executeRelationshipQuery(query, List.of("document"), options);

        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo(q4ContractId);
        assertThat(response.getDocuments().get(0).getContent()).contains("Contract - John Smith - Q4 2023");
        log.info("[LawFirm] Result documents: {}", response.getDocuments());
    }

    private void seedLawFirmData() {
        UserEntity johnSmith = new UserEntity();
        johnSmith.setFullName("John Smith");
        johnSmith.setEmail("john.smith@firm.test");

        UserEntity janeDoe = new UserEntity();
        janeDoe.setFullName("Jane Doe");
        janeDoe.setEmail("jane.doe@firm.test");

        DocumentEntity q4Contract = contractFor("Contract - John Smith - Q4 2023", "ACTIVE", johnSmith);
        DocumentEntity q3Contract = contractFor("Contract - John Smith - Q3 2023", "ACTIVE", johnSmith);
        DocumentEntity archivedContract = contractFor("Contract - John Smith - Q4 2023 (Archive)", "ARCHIVED", johnSmith);
        DocumentEntity otherClientContract = contractFor("Contract - Jane Doe - Q4 2023", "ACTIVE", janeDoe);

        userRepository.save(johnSmith);
        userRepository.save(janeDoe);

        q4ContractId = q4Contract.getId();

        entityRelationshipMapper.registerEntityType(DocumentEntity.class);
        entityRelationshipMapper.registerEntityType(UserEntity.class);
        entityRelationshipMapper.registerRelationship("document", "user", "author", RelationshipDirection.FORWARD, false);
    }

    private DocumentEntity contractFor(String title, String status, UserEntity author) {
        DocumentEntity document = new DocumentEntity();
        document.setTitle(title);
        document.setStatus(status);
        document.setAuthor(author);
        author.getDocuments().add(document);
        return document;
    }

    @TestConfiguration
    static class UseCaseVectorOverrides {

        @Bean
        public VectorDatabaseService vectorDatabaseService() {
            return Mockito.mock(VectorDatabaseService.class);
        }
    }

}
