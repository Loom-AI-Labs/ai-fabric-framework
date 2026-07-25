package ai.fabric.relationship.usecases;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.RAGResponse;
import ai.fabric.relationship.cache.QueryCache;
import ai.fabric.relationship.config.RelationshipModuleMetadata;
import ai.fabric.relationship.config.RelationshipQueryProperties;
import ai.fabric.relationship.dto.FilterCondition;
import ai.fabric.relationship.dto.FilterOperator;
import ai.fabric.relationship.dto.JpqlQuery;
import ai.fabric.relationship.dto.RelationshipDirection;
import ai.fabric.relationship.dto.RelationshipPath;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.dto.QueryStrategy;
import ai.fabric.relationship.integration.IntegrationTestSupport;
import ai.fabric.relationship.integration.RelationshipQueryTestApplication;
import ai.fabric.relationship.integration.entity.CandidateEntity;
import ai.fabric.relationship.integration.entity.RecruiterEntity;
import ai.fabric.relationship.integration.repository.CandidateRepository;
import ai.fabric.relationship.integration.repository.RecruiterRepository;
import ai.fabric.relationship.metrics.QueryMetrics;
import ai.fabric.relationship.model.QueryOptions;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.relationship.service.DefaultRelationshipQueryDocumentMapper;
import ai.fabric.relationship.service.DynamicJPAQueryBuilder;
import ai.fabric.relationship.service.LLMDrivenJPAQueryService;
import ai.fabric.relationship.service.RelationshipQueryPlanner;
import ai.fabric.relationship.service.RelationshipQueryDocumentMapper;
import ai.fabric.relationship.support.RelationshipProjectionTestSupport;
import ai.fabric.relationship.validation.RelationshipQueryValidator;
import ai.fabric.relationship.service.EntityRelationshipMapper;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.core.AIEmbeddingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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
@Import(HRCandidateSearchTest.VectorOverrides.class)
class HRCandidateSearchTest {

    private static final Logger log = LoggerFactory.getLogger(HRCandidateSearchTest.class);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerCommonProperties(registry);
    }

    @Autowired
    private CandidateRepository candidateRepository;

    @Autowired
    private RecruiterRepository recruiterRepository;

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
    private String matchedCandidateId;

    @BeforeEach
    void setUp() {
        Mockito.reset(planner);
        candidateRepository.deleteAll();
        recruiterRepository.deleteAll();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ex) {
                log.warn("Unable to clear vectors from Lucene test index; continuing with fresh context", ex);
            }
        }

        seedCandidates();

        var schemaProvider = new ai.fabric.relationship.service.RelationshipSchemaProvider(
            entityManager,
            null,
            relationshipQueryProperties,
            entityRelationshipMapper
        );
        schemaProvider.refreshSchema();

        var jpaTraversalService = new ai.fabric.relationship.service.JpaRelationshipTraversalService(entityManager);
        RelationshipQueryDocumentMapper documentMapper =
            new DefaultRelationshipQueryDocumentMapper(
                RelationshipProjectionTestSupport.projectionService(
                    configurationLoader
                ),
                configurationLoader
            );

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
    void shouldFindSeniorMlCandidatesInNYCForRecruiterDana() {
        String query = "Show senior machine learning engineer candidates in New York managed by Dana Liu";

        FilterCondition locationFilter = FilterCondition.builder()
            .field("location")
            .operator(FilterOperator.ILIKE)
            .value("%new york%")
            .build();

        FilterCondition seniorityFilter = FilterCondition.builder()
            .field("seniority")
            .operator(FilterOperator.EQUALS)
            .value("SENIOR")
            .build();

        FilterCondition skillFilter = FilterCondition.builder()
            .field("primarySkill")
            .operator(FilterOperator.ILIKE)
            .value("%machine learning%")
            .build();

        RelationshipPath recruiterPath = RelationshipPath.builder()
            .fromEntityType("candidate")
            .relationshipType("recruiter")
            .toEntityType("recruiter")
            .direction(RelationshipDirection.FORWARD)
            .optional(false)
            .conditions(List.of(FilterCondition.builder()
                .field("fullName")
                .operator(FilterOperator.ILIKE)
                .value("%Dana Liu%")
                .build()))
            .build();

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery(query)
            .primaryEntityType("candidate")
            .candidateEntityTypes(List.of("candidate", "recruiter"))
            .relationshipPaths(List.of(recruiterPath))
            .directFilters(Map.of("candidate", List.of(locationFilter, seniorityFilter, skillFilter)))
            .queryStrategy(QueryStrategy.RELATIONSHIP)
            .returnMode(ReturnMode.FULL)
            .needsSemanticSearch(false)
            .limit(5)
            .build();

        when(planner.planQuery(eq(query), eq(List.of("candidate")))).thenReturn(plan);

        JpqlQuery jpqlQuery = dynamicJPAQueryBuilder.buildQuery(plan);
        log.info("[HR] User query: {}", query);
        log.info("[HR] Planner plan: {}", plan);
        log.info("[HR] JPQL: {}", jpqlQuery.getJpql());

        QueryOptions options = QueryOptions.builder()
            .returnMode(ReturnMode.FULL)
            .limit(5)
            .build();

        RAGResponse response = llmDrivenJPAQueryService.executeRelationshipQuery(query, List.of("candidate"), options);

        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo(matchedCandidateId);
        assertThat(response.getDocuments().get(0).getMetadata()).containsEntry("recruiter", "Dana Liu");
        log.info("[HR] Result documents: {}", response.getDocuments());
    }

    private void seedCandidates() {
        RecruiterEntity dana = new RecruiterEntity();
        dana.setFullName("Dana Liu");
        dana.setEmail("dliu@agency.example");

        RecruiterEntity ryan = new RecruiterEntity();
        ryan.setFullName("Ryan Patel");
        ryan.setEmail("ryan@agency.example");

        recruiterRepository.save(dana);
        recruiterRepository.save(ryan);

        CandidateEntity nySeniorMl = candidate(
            "Kim Alvarez",
            "New York, NY",
            "SENIOR",
            "Machine Learning",
            dana
        );

        CandidateEntity nyMidMl = candidate(
            "Jess Singh",
            "New York, NY",
            "MID",
            "Machine Learning",
            dana
        );

        CandidateEntity laSeniorMl = candidate(
            "Chris Lee",
            "Los Angeles, CA",
            "SENIOR",
            "Machine Learning",
            ryan
        );

        matchedCandidateId = nySeniorMl.getId();

        entityRelationshipMapper.registerEntityType(CandidateEntity.class);
        entityRelationshipMapper.registerEntityType(RecruiterEntity.class);
        try {
            entityRelationshipMapper.registerRelationship("candidate", "recruiter", "recruiter", RelationshipDirection.FORWARD, false);
        } catch (IllegalStateException ignored) { }
    }

    private CandidateEntity candidate(String name, String location, String seniority, String skill, RecruiterEntity recruiter) {
        CandidateEntity candidate = new CandidateEntity();
        candidate.setFullName(name);
        candidate.setLocation(location);
        candidate.setSeniority(seniority);
        candidate.setPrimarySkill(skill);
        candidate.setRecruiter(recruiter);
        recruiter.getCandidates().add(candidate);
        return candidateRepository.save(candidate);
    }

    @TestConfiguration
    static class VectorOverrides {

        @Bean
        public VectorDatabaseService vectorDatabaseService() {
            return Mockito.mock(VectorDatabaseService.class);
        }
    }

}
