package com.ai.infrastructure.relationship.integration;

import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.core.AIEmbeddingService;
import com.ai.infrastructure.relationship.config.RelationshipQueryAutoConfiguration;
import com.ai.infrastructure.relationship.cache.QueryCache;
import com.ai.infrastructure.relationship.config.RelationshipModuleMetadata;
import com.ai.infrastructure.relationship.config.RelationshipQueryProperties;
import com.ai.infrastructure.relationship.dto.FilterCondition;
import com.ai.infrastructure.relationship.dto.FilterOperator;
import com.ai.infrastructure.relationship.dto.RelationshipDirection;
import com.ai.infrastructure.relationship.dto.RelationshipPath;
import com.ai.infrastructure.relationship.dto.RelationshipQueryPlan;
import com.ai.infrastructure.relationship.dto.QueryStrategy;
import com.ai.infrastructure.relationship.integration.entity.BrandEntity;
import com.ai.infrastructure.relationship.integration.entity.DocumentEntity;
import com.ai.infrastructure.relationship.integration.entity.ProductEntity;
import com.ai.infrastructure.relationship.integration.entity.UserEntity;
import com.ai.infrastructure.relationship.integration.entity.PatientEntity;
import com.ai.infrastructure.relationship.integration.entity.MedicalCaseEntity;
import com.ai.infrastructure.relationship.integration.entity.CandidateEntity;
import com.ai.infrastructure.relationship.integration.entity.RecruiterEntity;
import com.ai.infrastructure.relationship.integration.entity.AccountEntity;
import com.ai.infrastructure.relationship.integration.entity.TransactionEntity;
import com.ai.infrastructure.relationship.integration.repository.DocumentRepository;
import com.ai.infrastructure.relationship.integration.repository.BrandRepository;
import com.ai.infrastructure.relationship.integration.repository.ProductRepository;
import com.ai.infrastructure.relationship.integration.repository.UserRepository;
import com.ai.infrastructure.relationship.integration.repository.PatientRepository;
import com.ai.infrastructure.relationship.integration.repository.MedicalCaseRepository;
import com.ai.infrastructure.relationship.integration.repository.CandidateRepository;
import com.ai.infrastructure.relationship.integration.repository.RecruiterRepository;
import com.ai.infrastructure.relationship.integration.repository.AccountRepository;
import com.ai.infrastructure.relationship.integration.repository.TransactionRepository;
import com.ai.infrastructure.relationship.model.QueryOptions;
import com.ai.infrastructure.relationship.model.ReturnMode;
import com.ai.infrastructure.relationship.metrics.QueryMetrics;
import com.ai.infrastructure.relationship.service.DynamicJPAQueryBuilder;
import com.ai.infrastructure.relationship.service.LLMDrivenJPAQueryService;
import com.ai.infrastructure.relationship.service.RelationshipQueryPlanner;
import com.ai.infrastructure.relationship.service.DefaultRelationshipQueryDocumentMapper;
import com.ai.infrastructure.relationship.service.RelationshipQueryDocumentMapper;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.relationship.validation.RelationshipQueryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.EntityManager;
import com.ai.infrastructure.repository.IntentHistoryRepository;
import com.ai.infrastructure.entity.IntentHistory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@SpringBootTest(
    classes = RelationshipQueryTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("integration")
public class RelationshipQueryIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RelationshipQueryIntegrationTest.class);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerCommonProperties(registry);
    }

    private LLMDrivenJPAQueryService llmDrivenJPAQueryService;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AIEntityConfigurationLoader configurationLoader;

    @Autowired
    private VectorDatabaseService vectorDatabaseService;

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
    private AIEmbeddingService aiEmbeddingService;

    @Autowired
    private QueryCache queryCache;

    @Autowired
    private QueryMetrics queryMetrics;

    @Autowired
    private com.ai.infrastructure.relationship.service.EntityRelationshipMapper entityRelationshipMapper;

    @PersistenceContext
    private EntityManager entityManager;

    private String activeDocumentId;

    @BeforeEach
    void setUpData() {
        documentRepository.deleteAll();
        userRepository.deleteAll();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ex) {
                log.warn("Unable to clear vectors from Lucene test index; continuing with fresh context", ex);
            }
        }

        UserEntity author = new UserEntity();
        author.setFullName("Ada Lovelace");
        author.setEmail("ada@example.com");

        DocumentEntity document = new DocumentEntity();
        document.setTitle("LLM Guardrails Playbook");
        document.setStatus("ACTIVE");
        document.setAuthor(author);
        author.getDocuments().add(document);

        userRepository.save(author);
        activeDocumentId = document.getId();

        entityRelationshipMapper.registerEntityType(DocumentEntity.class);
        entityRelationshipMapper.registerEntityType(UserEntity.class);
        entityRelationshipMapper.registerRelationship("document", "user", "author", RelationshipDirection.FORWARD, false);

        com.ai.infrastructure.relationship.service.RelationshipSchemaProvider schemaProvider =
            new com.ai.infrastructure.relationship.service.RelationshipSchemaProvider(
                entityManager,
                null,
                relationshipQueryProperties,
                entityRelationshipMapper
            );
        schemaProvider.refreshSchema();

        com.ai.infrastructure.relationship.service.RelationshipTraversalService jpaTraversalService =
            new com.ai.infrastructure.relationship.service.JpaRelationshipTraversalService(entityManager);
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
    void shouldExecuteEndToEndThroughJpaTraversal() {
        RelationshipQueryPlan plan = buildPlan();
        when(planner.planQuery(anyString(), anyList())).thenReturn(plan);

        RAGResponse response = llmDrivenJPAQueryService.executeRelationshipQuery(
            "active docs by ada",
            List.of("document"),
            QueryOptions.builder()
                .returnMode(ReturnMode.FULL)
                .limit(5)
                .build()
        );

        assertThat(response.getDocuments()).extracting(RAGResponse.RAGDocument::getId)
            .containsExactly(activeDocumentId);
        assertThat(response.getEntityType()).isEqualTo("document");
        assertThat(response.getDocuments().get(0).getContent()).isEqualTo("LLM Guardrails Playbook");
    }

    private RelationshipQueryPlan buildPlan() {
        FilterCondition statusFilter = FilterCondition.builder()
            .field("status")
            .operator(FilterOperator.EQUALS)
            .value("ACTIVE")
            .build();

        RelationshipPath authorPath = RelationshipPath.builder()
            .fromEntityType("document")
            .relationshipType("author")
            .toEntityType("user")
            .direction(RelationshipDirection.FORWARD)
            .optional(false)
            .build();

        return RelationshipQueryPlan.builder()
            .originalQuery("active docs by ada")
            .primaryEntityType("document")
            .candidateEntityTypes(List.of("document", "user"))
            .relationshipPaths(List.of(authorPath))
            .directFilters(Map.of("document", List.of(statusFilter)))
            .needsSemanticSearch(false)
            .queryStrategy(QueryStrategy.RELATIONSHIP)
            .limit(5)
            .returnMode(ReturnMode.FULL)
            .build();
    }
}
