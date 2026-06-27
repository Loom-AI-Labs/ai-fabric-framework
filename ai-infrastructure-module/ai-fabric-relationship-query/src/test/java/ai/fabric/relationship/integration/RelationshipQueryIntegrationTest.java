package ai.fabric.relationship.integration;

import ai.fabric.dto.RAGResponse;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.relationship.config.RelationshipQueryAutoConfiguration;
import ai.fabric.relationship.cache.QueryCache;
import ai.fabric.relationship.config.RelationshipModuleMetadata;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.dto.FilterCondition;
import ai.fabric.relationship.dto.FilterOperator;
import ai.fabric.relationship.dto.RelationshipDirection;
import ai.fabric.relationship.dto.RelationshipPath;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.dto.QueryStrategy;
import ai.fabric.relationship.integration.entity.BrandEntity;
import ai.fabric.relationship.integration.entity.DocumentEntity;
import ai.fabric.relationship.integration.entity.ProductEntity;
import ai.fabric.relationship.integration.entity.UserEntity;
import ai.fabric.relationship.integration.entity.PatientEntity;
import ai.fabric.relationship.integration.entity.MedicalCaseEntity;
import ai.fabric.relationship.integration.entity.CandidateEntity;
import ai.fabric.relationship.integration.entity.RecruiterEntity;
import ai.fabric.relationship.integration.entity.AccountEntity;
import ai.fabric.relationship.integration.entity.TransactionEntity;
import ai.fabric.relationship.integration.repository.DocumentRepository;
import ai.fabric.relationship.integration.repository.BrandRepository;
import ai.fabric.relationship.integration.repository.ProductRepository;
import ai.fabric.relationship.integration.repository.UserRepository;
import ai.fabric.relationship.integration.repository.PatientRepository;
import ai.fabric.relationship.integration.repository.MedicalCaseRepository;
import ai.fabric.relationship.integration.repository.CandidateRepository;
import ai.fabric.relationship.integration.repository.RecruiterRepository;
import ai.fabric.relationship.integration.repository.AccountRepository;
import ai.fabric.relationship.integration.repository.TransactionRepository;
import ai.fabric.relationship.model.QueryOptions;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.relationship.metrics.QueryMetrics;
import ai.fabric.relationship.service.DynamicJPAQueryBuilder;
import ai.fabric.relationship.service.LLMDrivenJPAQueryService;
import ai.fabric.relationship.service.RelationshipQueryPlanner;
import ai.fabric.relationship.service.DefaultRelationshipQueryDocumentMapper;
import ai.fabric.relationship.service.RelationshipQueryDocumentMapper;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.relationship.validation.RelationshipQueryValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
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
import ai.fabric.repository.IntentHistoryRepository;
import ai.fabric.entity.IntentHistory;

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
    private ai.fabric.relationship.service.EntityRelationshipMapper entityRelationshipMapper;

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

        ai.fabric.relationship.service.RelationshipSchemaProvider schemaProvider =
            new ai.fabric.relationship.service.RelationshipSchemaProvider(
                entityManager,
                null,
                relationshipQueryProperties,
                entityRelationshipMapper
            );
        schemaProvider.refreshSchema();

        ai.fabric.relationship.service.RelationshipTraversalService jpaTraversalService =
            new ai.fabric.relationship.service.JpaRelationshipTraversalService(entityManager);
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
