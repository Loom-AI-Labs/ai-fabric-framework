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
import com.ai.infrastructure.relationship.integration.entity.CandidateEntity;
import com.ai.infrastructure.relationship.integration.entity.RecruiterEntity;
import com.ai.infrastructure.relationship.integration.repository.CandidateRepository;
import com.ai.infrastructure.relationship.integration.repository.RecruiterRepository;
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
