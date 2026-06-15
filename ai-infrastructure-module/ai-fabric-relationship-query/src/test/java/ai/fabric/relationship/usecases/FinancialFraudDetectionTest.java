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
import ai.fabric.relationship.integration.entity.AccountEntity;
import ai.fabric.relationship.integration.entity.TransactionEntity;
import ai.fabric.relationship.integration.repository.AccountRepository;
import ai.fabric.relationship.integration.repository.TransactionRepository;
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
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.math.BigDecimal;
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
@Import(FinancialFraudDetectionTest.VectorOverrides.class)
class FinancialFraudDetectionTest {

    private static final Logger log = LoggerFactory.getLogger(FinancialFraudDetectionTest.class);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        IntegrationTestSupport.registerCommonProperties(registry);
    }

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private TransactionRepository transactionRepository;

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
    private String flaggedTransactionId;

    @BeforeEach
    void setUp() {
        Mockito.reset(planner);
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ex) {
                log.warn("Unable to clear vectors from Lucene test index; continuing with fresh context", ex);
            }
        }

        seedFinancialData();

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
    void shouldFindHighRiskCrossBorderTransactionsBetweenLinkedAccounts() {
        String query = "List suspicious transactions over $25k from high-risk regions routed through the same counterparty";

        FilterCondition amountFilter = FilterCondition.builder()
            .field("amount")
            .operator(FilterOperator.GREATER_THAN)
            .value(BigDecimal.valueOf(25000))
            .build();

        FilterCondition statusFilter = FilterCondition.builder()
            .field("status")
            .operator(FilterOperator.EQUALS)
            .value("PENDING_REVIEW")
            .build();

        FilterCondition channelFilter = FilterCondition.builder()
            .field("channel")
            .operator(FilterOperator.ILIKE)
            .value("%wire%")
            .build();

        RelationshipPath counterpartyPath = RelationshipPath.builder()
            .fromEntityType("transaction")
            .relationshipType("destinationAccount")
            .toEntityType("destination-account")
            .direction(RelationshipDirection.FORWARD)
            .optional(false)
            .conditions(List.of(FilterCondition.builder()
                .field("region")
                .operator(FilterOperator.ILIKE)
                .value("%high-risk%")
                .build()))
            .build();

        RelationshipPath originPath = RelationshipPath.builder()
            .fromEntityType("transaction")
            .relationshipType("sourceAccount")
            .toEntityType("origin-account")
            .direction(RelationshipDirection.FORWARD)
            .optional(false)
            .conditions(List.of(FilterCondition.builder()
                .field("riskScore")
                .operator(FilterOperator.GREATER_THAN_OR_EQUAL)
                .value(BigDecimal.valueOf(0.7))
                .build()))
            .build();

        RelationshipQueryPlan plan = RelationshipQueryPlan.builder()
            .originalQuery(query)
            .primaryEntityType("transaction")
            .candidateEntityTypes(List.of("transaction", "account"))
            .relationshipPaths(List.of(counterpartyPath, originPath))
            .directFilters(Map.of("transaction", List.of(amountFilter, statusFilter, channelFilter)))
            .queryStrategy(QueryStrategy.RELATIONSHIP)
            .returnMode(ReturnMode.FULL)
            .needsSemanticSearch(false)
            .limit(5)
            .build();

        when(planner.planQuery(eq(query), eq(List.of("transaction")))).thenReturn(plan);

        JpqlQuery jpqlQuery = dynamicJPAQueryBuilder.buildQuery(plan);
        log.info("[Fraud] User query: {}", query);
        log.info("[Fraud] Planner plan: {}", plan);
        log.info("[Fraud] JPQL: {}", jpqlQuery.getJpql());

        QueryOptions options = QueryOptions.builder()
            .returnMode(ReturnMode.FULL)
            .limit(5)
            .build();

        RAGResponse response = llmDrivenJPAQueryService.executeRelationshipQuery(query, List.of("transaction"), options);

        assertThat(response.getDocuments()).hasSize(1);
        assertThat(response.getDocuments().get(0).getId()).isEqualTo(flaggedTransactionId);
        assertThat(response.getDocuments().get(0).getMetadata()).containsEntry("channel", "Wire");
        log.info("[Fraud] Result documents: {}", response.getDocuments());
    }

    private void seedFinancialData() {
        AccountEntity highRiskOrigin = account("Helios Imports", "high-risk region", BigDecimal.valueOf(0.83));
        AccountEntity counterpart = account("Nordic Clearing", "high-risk corridor", BigDecimal.valueOf(0.22));
        AccountEntity benignOrigin = account("Sunrise Foods", "stable region", BigDecimal.valueOf(0.35));

        transactionRepository.save(transaction(
            "Pending Wire 40k",
            BigDecimal.valueOf(40000),
            "USD",
            "Wire",
            "PENDING_REVIEW",
            LocalDateTime.now().minusHours(5),
            highRiskOrigin,
            counterpart,
            true
        ));

        transactionRepository.save(transaction(
            "Cleared Wire 32k",
            BigDecimal.valueOf(32000),
            "USD",
            "Wire",
            "CLEARED",
            LocalDateTime.now().minusHours(30),
            highRiskOrigin,
            counterpart,
            false
        ));

        transactionRepository.save(transaction(
            "ACH 50k Stable",
            BigDecimal.valueOf(50000),
            "USD",
            "ACH",
            "PENDING_REVIEW",
            LocalDateTime.now().minusHours(2),
            benignOrigin,
            counterpart,
            false
        ));

        entityRelationshipMapper.registerEntityType(TransactionEntity.class);
        entityRelationshipMapper.registerEntityType(AccountEntity.class);
        entityRelationshipMapper.registerEntityType("destination-account", AccountEntity.class);
        entityRelationshipMapper.registerEntityType("origin-account", AccountEntity.class);
        try {
            entityRelationshipMapper.registerRelationship("transaction", "destination-account", "destinationAccount", RelationshipDirection.FORWARD, false);
        } catch (IllegalStateException ignored) { }
        try {
            entityRelationshipMapper.registerRelationship("transaction", "origin-account", "sourceAccount", RelationshipDirection.FORWARD, false);
        } catch (IllegalStateException ignored) { }
    }

    private AccountEntity account(String owner, String region, BigDecimal risk) {
        AccountEntity account = new AccountEntity();
        account.setOwnerName(owner);
        account.setRegion(region);
        account.setRiskScore(risk);
        return accountRepository.save(account);
    }

    private TransactionEntity transaction(String title,
                                          BigDecimal amount,
                                          String currency,
                                          String channel,
                                          String status,
                                          LocalDateTime occurred,
                                          AccountEntity source,
                                          AccountEntity destination,
                                          boolean flagged) {
        TransactionEntity tx = new TransactionEntity();
        tx.setTitle(title);
        tx.setAmount(amount);
        tx.setCurrency(currency);
        tx.setChannel(channel);
        tx.setStatus(status);
        tx.setOccurredAt(occurred);
        tx.setSourceAccount(source);
        tx.setDestinationAccount(destination);
        tx = transactionRepository.save(tx);
        flaggedTransactionId = flagged ? tx.getId() : flaggedTransactionId;
        return tx;
    }

    @TestConfiguration
    static class VectorOverrides {

        @Bean
        public VectorDatabaseService vectorDatabaseService() {
            return Mockito.mock(VectorDatabaseService.class);
        }
    }

}
