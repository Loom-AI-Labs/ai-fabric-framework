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
import ai.fabric.relationship.integration.entity.MedicalCaseEntity;
import ai.fabric.relationship.integration.entity.PatientEntity;
import ai.fabric.relationship.integration.repository.MedicalCaseRepository;
import ai.fabric.relationship.integration.repository.PatientRepository;
import ai.fabric.relationship.metrics.QueryMetrics;
import ai.fabric.relationship.model.QueryOptions;
import ai.fabric.relationship.model.ReturnMode;
import ai.fabric.relationship.service.DefaultRelationshipQueryDocumentMapper;
import ai.fabric.relationship.service.DynamicJPAQueryBuilder;
import ai.fabric.relationship.service.LLMDrivenJPAQueryService;
import ai.fabric.relationship.service.RelationshipQueryPlanner;
import ai.fabric.relationship.service.RelationshipQueryDocumentMapper;
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

import java.time.LocalDate;
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
@Import(MedicalCaseFinderTest.VectorOverrides.class)
class MedicalCaseFinderTest {

    private static final Logger log = LoggerFactory.getLogger(MedicalCaseFinderTest.class);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerCommonProperties(registry);
    }

    @Autowired
    private MedicalCaseRepository medicalCaseRepository;

    @Autowired
    private PatientRepository patientRepository;

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
    private String oncologyCaseId;

    @BeforeEach
    void setUp() {
        Mockito.reset(planner);
        medicalCaseRepository.deleteAll();
        patientRepository.deleteAll();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ex) {
                log.warn("Unable to clear vectors from Lucene test index; continuing with fresh context", ex);
            }
        }

        seedMedicalData();

        var schemaProvider = new ai.fabric.relationship.service.RelationshipSchemaProvider(
            entityManager,
            null,
            relationshipQueryProperties,
            entityRelationshipMapper
        );
        schemaProvider.refreshSchema();

        var jpaTraversalService = new ai.fabric.relationship.service.JpaRelationshipTraversalService(entityManager);
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
    void shouldFindActiveOncologyImmunotherapyCasesForAlice() {
        String query = "Find active oncology cases for Alice Carter that require immunotherapy";

        FilterCondition specialtyFilter = FilterCondition.builder()
            .field("specialty")
            .operator(FilterOperator.ILIKE)
            .value("oncology")
            .build();

        FilterCondition therapyFilter = FilterCondition.builder()
            .field("therapyPlan")
            .operator(FilterOperator.ILIKE)
            .value("%immunotherapy%")
            .build();

        FilterCondition statusFilter = FilterCondition.builder()
            .field("status")
            .operator(FilterOperator.EQUALS)
            .value("ACTIVE")
            .build();

        RelationshipPath patientPath = RelationshipPath.builder()
            .fromEntityType("medical-case")
            .relationshipType("patient")
            .toEntityType("patient")
            .direction(RelationshipDirection.FORWARD)
            .optional(false)
            .conditions(List.of(FilterCondition.builder()
                .field("fullName")
                .operator(FilterOperator.ILIKE)
                .value("%Alice Carter%")
                .build()))
            .build();

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery(query)
            .primaryEntityType("medical-case")
            .candidateEntityTypes(List.of("medical-case", "patient"))
            .relationshipPaths(List.of(patientPath))
            .directFilters(Map.of("medical-case", List.of(specialtyFilter, therapyFilter, statusFilter)))
            .queryStrategy(QueryStrategy.RELATIONSHIP)
            .returnMode(ReturnMode.FULL)
            .needsSemanticSearch(false)
            .limit(5)
            .build();

        when(planner.planQuery(eq(query), eq(List.of("medical-case")))).thenReturn(plan);

        JpqlQuery jpqlQuery = dynamicJPAQueryBuilder.buildQuery(plan);
        log.info("[Medical] User query: {}", query);
        log.info("[Medical] Planner plan: {}", plan);
        log.info("[Medical] JPQL: {}", jpqlQuery.getJpql());

        QueryOptions options = QueryOptions.builder()
            .returnMode(ReturnMode.FULL)
            .limit(5)
            .build();

        RAGResponse response = llmDrivenJPAQueryService.executeRelationshipQuery(query, List.of("medical-case"), options);

        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo(oncologyCaseId);
        assertThat(response.getDocuments().get(0).getContent()).contains("Alice Carter");
        log.info("[Medical] Result documents: {}", response.getDocuments());
    }

    private void seedMedicalData() {
        PatientEntity alice = new PatientEntity();
        alice.setFullName("Alice Carter");
        alice.setDateOfBirth(LocalDate.of(1984, 5, 12));

        PatientEntity bob = new PatientEntity();
        bob.setFullName("Bob Jensen");
        bob.setDateOfBirth(LocalDate.of(1978, 2, 8));

        patientRepository.save(alice);
        patientRepository.save(bob);

        MedicalCaseEntity activeOncology = medicalCase(
            "Alice Carter Oncology Case",
            "oncology",
            "Immunotherapy + monitoring",
            "ACTIVE",
            alice
        );
        MedicalCaseEntity inactiveOncology = medicalCase(
            "Alice Carter Oncology Case (Closed)",
            "oncology",
            "Radiation therapy",
            "CLOSED",
            alice
        );
        MedicalCaseEntity cardiologyBob = medicalCase(
            "Bob Jensen Cardiology Case",
            "cardiology",
            "Statin therapy",
            "ACTIVE",
            bob
        );

        oncologyCaseId = activeOncology.getId();

        entityRelationshipMapper.registerEntityType(MedicalCaseEntity.class);
        entityRelationshipMapper.registerEntityType(PatientEntity.class);
        try {
            entityRelationshipMapper.registerRelationship("medical-case", "patient", "patient", RelationshipDirection.FORWARD, false);
        } catch (IllegalStateException ignored) { }
    }

    private MedicalCaseEntity medicalCase(String title, String specialty, String therapy, String status, PatientEntity patient) {
        MedicalCaseEntity medicalCase = new MedicalCaseEntity();
        medicalCase.setTitle(title);
        medicalCase.setSpecialty(specialty);
        medicalCase.setTherapyPlan(therapy);
        medicalCase.setStatus(status);
        medicalCase.setPatient(patient);
        patient.getMedicalCases().add(medicalCase);
        return medicalCaseRepository.save(medicalCase);
    }

    @TestConfiguration
    static class VectorOverrides {

        @Bean
        public VectorDatabaseService vectorDatabaseService() {
            return Mockito.mock(VectorDatabaseService.class);
        }
    }

}
