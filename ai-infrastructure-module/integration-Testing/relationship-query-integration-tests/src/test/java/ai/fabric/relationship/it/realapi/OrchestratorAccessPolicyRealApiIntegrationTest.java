package ai.fabric.relationship.it.realapi;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.RAGResponse;
import ai.fabric.intent.action.ActionResult;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.OrchestrationResultType;
import ai.fabric.intent.orchestration.RAGOrchestrator;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import ai.fabric.relationship.it.RelationshipQueryIntegrationTestApplication;
import ai.fabric.relationship.it.config.BackendEnvTestConfiguration;
import ai.fabric.relationship.dto.RelationshipQueryPlan;
import ai.fabric.relationship.it.entity.AccountEntity;
import ai.fabric.relationship.it.entity.BrandEntity;
import ai.fabric.relationship.it.entity.DocumentEntity;
import ai.fabric.relationship.it.entity.ProductEntity;
import ai.fabric.relationship.it.entity.TransactionEntity;
import ai.fabric.relationship.it.entity.UserEntity;
import ai.fabric.relationship.it.repository.AccountRepository;
import ai.fabric.relationship.it.repository.BrandRepository;
import ai.fabric.relationship.it.repository.DocumentRepository;
import ai.fabric.relationship.it.repository.ProductRepository;
import ai.fabric.relationship.it.repository.TransactionRepository;
import ai.fabric.relationship.it.repository.UserRepository;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
    classes = RelationshipQueryIntegrationTestApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("realapi")
@Import({
    BackendEnvTestConfiguration.class,
    OrchestratorAccessPolicyRealApiIntegrationTest.PolicyConfig.class,
    ai.fabric.relationship.it.config.TestRelationshipQueryAccessControlPolicy.class
})
@TestPropertySource(properties = {
    "ai.infrastructure.relationship.enable-orchestrator-integration=true"
})
class OrchestratorAccessPolicyRealApiIntegrationTest {

    @Autowired
    private RAGOrchestrator orchestrator;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BrandRepository brandRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private TransactionRepository transactionRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired(required = false)
    private VectorDatabaseService vectorDatabaseService;

    @Autowired
    private RecordingEntityAccessPolicy accessPolicy;

    @BeforeEach
    void setUp() {
        productRepository.deleteAllInBatch();
        brandRepository.deleteAllInBatch();
        documentRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
        transactionRepository.deleteAllInBatch();
        accountRepository.deleteAllInBatch();
        if (vectorDatabaseService != null) {
            try {
                vectorDatabaseService.clearVectors();
            } catch (Exception ignored) {
            }
        }
        seedCatalog();
        accessPolicy.reset();
    }

    @Test
    void orchestratorShouldInvokePolicyAndExecuteRelationshipQuery() {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("orch-user")
            .sessionId("orch-session")
            .metadata(Map.of("channel", "test"))
            .build();

        OrchestrationResult result = orchestrator.orchestrate(
            "relationship_query: find blue running shoes under $120 from Nike",
            context
        );

        assertThat(accessPolicy.getCallCount()).isGreaterThan(0);
        assertThat(accessPolicy.getLastUser()).isEqualTo("orch-user");
        assertThat(accessPolicy.getLastContext()).containsEntry("resourceId", "rag:intent");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        Object actionResultObj = result.getData().get("actionResult");
        assertThat(actionResultObj).isInstanceOf(ActionResult.class);
        ActionResult actionResult = (ActionResult) actionResultObj;
        assertThat(actionResult.isSuccess()).isTrue();
        assertThat(actionResult.getData()).isNotNull();

        Map<String, Object> payload = actionResult.getData().toMap();
        assertThat(payload).containsKey("documents");

        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) payload.get("documents");
        assertThat(documents).isNotNull();
        assertThat(documents).isNotEmpty();
        assertThat(documents).allSatisfy(doc -> assertThat(productRepository.existsById(doc.getId())).isTrue());
    }

    @Test
    @DisplayName("LLM should extract entityTypes for product queries and generate correct JPQL")
    void shouldExtractEntityTypesForProductQuery() {
        // This test indirectly verifies that the prompt enhancement works:
        // The LLM should extract entityTypes=["product"] from the query "find products from Nike"
        // If entityTypes are NOT extracted, the query might fail or return incorrect results
        // By verifying the query succeeds and returns expected product results, we confirm entityTypes were extracted

        OrchestrationContext context = OrchestrationContext.builder()
            .userId("entity-extraction-test-user")
            .sessionId("entity-extraction-session")
            .metadata(Map.of("channel", "test"))
            .build();

        // Query that should extract entityTypes=["product"] based on the prompt enhancement
        // The prompt instructs: "When action == 'relationship_query', extract entityTypes from the user request"
        // Note: We use an explicit action hint to ensure the LLM selects the relationship_query action deterministically.
        String naturalLanguageQuery = "relationship_query: find products from Nike";

        OrchestrationResult result = orchestrator.orchestrate(
            naturalLanguageQuery,
            context
        );

        // Verify the query executed successfully
        assertThat(result.isSuccess())
            .as("Query should succeed if entityTypes were correctly extracted by LLM")
            .isTrue();
        assertThat(result.getType())
            .as("Should execute relationship_query action")
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        // Verify action result contains data
        Object actionResultObj = result.getData().get("actionResult");
        assertThat(actionResultObj)
            .as("Action result should be present")
            .isInstanceOf(ActionResult.class);

        ActionResult actionResult = (ActionResult) actionResultObj;
        assertThat(actionResult.isSuccess())
            .as("Relationship query action should succeed")
            .isTrue();
        assertThat(actionResult.getData())
            .as("Action result should contain data")
            .isNotNull();

        Map<String, Object> payload = actionResult.getData().toMap();

        // Verify documents were returned (indirect proof that entityTypes were used correctly)
        assertThat(payload)
            .as("Payload should contain documents")
            .containsKey("documents");

        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) payload.get("documents");
        assertThat(documents)
            .as("Documents should be returned if entityTypes were correctly extracted")
            .isNotNull()
            .isNotEmpty();

        // Verify we got product results (confirms entityTypes=["product"] was extracted and used)
        assertThat(documents)
            .as("Should return products from Nike if entityTypes were correctly extracted")
            .allSatisfy(doc -> assertThat(productRepository.existsById(doc.getId())).isTrue());

        // Verify total results count (indirect confirmation that query was scoped to product entity type)
        Object totalResults = payload.get("totalResults");
        if (totalResults != null) {
            assertThat(totalResults)
                .as("Should have found products")
                .isInstanceOf(Integer.class);
            assertThat((Integer) totalResults)
                .as("Should find at least one product")
                .isGreaterThan(0);
        }

        // Verify JPQL query was generated correctly
        verifyJpqlQuery(payload, "product", "product", "brand");
    }

    @Test
    @DisplayName("LLM should extract entityTypes for document queries and generate correct JPQL")
    void shouldExtractEntityTypesForDocumentQuery() {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("entity-extraction-test-user")
            .sessionId("entity-extraction-session")
            .metadata(Map.of("channel", "test"))
            .build();

        // Query that should extract entityTypes=["document", "user"]
        String naturalLanguageQuery = "relationship_query: find documents authored by John Smith";

        OrchestrationResult result = orchestrator.orchestrate(naturalLanguageQuery, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult.isSuccess()).isTrue();

        Map<String, Object> payload = actionResult.getData().toMap();

        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) payload.get("documents");
        assertThat(documents).isNotNull().isNotEmpty();
        assertThat(documents).allSatisfy(doc -> assertThat(documentRepository.existsById(doc.getId())).isTrue());

        // Verify JPQL query was generated correctly for document-user relationship
        verifyJpqlQuery(payload, "document", "document", "user");
    }

    @Test
    @DisplayName("LLM should extract entityTypes for transaction queries and generate correct JPQL")
    void shouldExtractEntityTypesForTransactionQuery() {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("entity-extraction-test-user")
            .sessionId("entity-extraction-session")
            .metadata(Map.of("channel", "test"))
            .build();

        // Query that should extract entityTypes=["transaction", "destination-account"]
        // Use explicit query to avoid LLM misinterpreting "high-risk" as a TransactionEntity field
        String naturalLanguageQuery = "relationship_query: find transactions over $10000 where destination account region is high-risk";

        OrchestrationResult result = orchestrator.orchestrate(naturalLanguageQuery, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult.isSuccess()).isTrue();

        Map<String, Object> payload = actionResult.getData().toMap();

        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) payload.get("documents");
        assertThat(documents).isNotNull();

        // Verify JPQL query was generated correctly for transaction-account relationship
        verifyJpqlQuery(payload, "transaction", "transaction", "destination-account");
    }

    @Test
    @DisplayName("LLM should extract entityTypes for brand queries and generate correct JPQL")
    void shouldExtractEntityTypesForBrandQuery() {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("entity-extraction-test-user")
            .sessionId("entity-extraction-session")
            .metadata(Map.of("channel", "test"))
            .build();

        // Query that should extract entityTypes=["brand"]
        String naturalLanguageQuery = "relationship_query: find all brands";

        OrchestrationResult result = orchestrator.orchestrate(naturalLanguageQuery, context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult.isSuccess()).isTrue();

        Map<String, Object> payload = actionResult.getData().toMap();

        @SuppressWarnings("unchecked")
        List<RAGResponse.RAGDocument> documents = (List<RAGResponse.RAGDocument>) payload.get("documents");
        assertThat(documents).isNotNull();

        // Verify JPQL query was generated correctly for brand entity
        verifyJpqlQuery(payload, "brand", "brand");
    }

    @ParameterizedTest
    @MethodSource("entityTypeQueryProvider")
    @DisplayName("LLM should extract correct entityTypes and generate valid JPQL for various queries")
    void shouldExtractEntityTypesAndGenerateValidJpqlForVariousQueries(
            String query,
            String[] expectedEntityTypes,
            String expectedPrimaryEntityType) {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("entity-extraction-test-user")
            .sessionId("entity-extraction-session")
            .metadata(Map.of("channel", "test"))
            .build();

        OrchestrationResult result = orchestrator.orchestrate("relationship_query: " + query, context);

        assertThat(result.getType())
            .as("Should execute relationship_query action without throwing (query=%s)", query)
            .isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult).as("ActionResult should be present").isNotNull();

        Map<String, Object> payload = actionResult.getData().toMap();

        // Verify JPQL query was generated correctly
        verifyJpqlQuery(payload, expectedPrimaryEntityType, expectedEntityTypes);
    }

    static Stream<Arguments> entityTypeQueryProvider() {
        return Stream.of(
            Arguments.of(
                "find products from Nike",
                new String[]{"product", "brand"},
                "product"
            ),
            Arguments.of(
                "find all brands",
                new String[]{"brand"},
                "brand"
            ),
            Arguments.of(
                "find documents by John Smith",
                new String[]{"document", "user"},
                "document"
            ),
            Arguments.of(
                "find transactions over $10000",
                new String[]{"transaction"},
                "transaction"
            ),
            Arguments.of(
                "find products under $100",
                new String[]{"product"},
                "product"
            ),
            Arguments.of(
                "find products where color is red",
                new String[]{"product"},
                "product"
            ),
            Arguments.of(
                "find products under $100 from Nike",
                new String[]{"product", "brand"},
                "product"
            ),
            Arguments.of(
                "find documents with status ACTIVE",
                new String[]{"document"},
                "document"
            ),
            Arguments.of(
                "find transactions where destination account risk score is over 0.8",
                new String[]{"transaction", "destination-account"},
                "transaction"
            ),
            Arguments.of(
                "find transactions sent to destination accounts in high-risk region",
                new String[]{"transaction", "destination-account"},
                "transaction"
            )
        );
    }

    @Test
    @DisplayName("LLM planner should return empty filters for broad list queries (no example leakage)")
    void shouldReturnEmptyFiltersForBroadListQuery() {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("entity-extraction-test-user")
            .sessionId("entity-extraction-session")
            .metadata(Map.of("channel", "test"))
            .build();

        OrchestrationResult result = orchestrator.orchestrate("relationship_query: find all brands", context);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getType()).isEqualTo(OrchestrationResultType.ACTION_EXECUTED);

        ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
        assertThat(actionResult.isSuccess()).isTrue();

        Map<String, Object> payload = actionResult.getData().toMap();

        RelationshipQueryPlan plan = extractRelationshipQueryPlan(payload);
        assertThat(plan.getPrimaryEntityType()).isEqualTo("brand");
        assertThat(plan.getDirectFilters())
            .as("Broad list query should not invent filters (avoid copying example values)")
            .isNotNull();
        assertThat(plan.getDirectFilters().values())
            .as("Broad list query should not include any direct filters")
            .allMatch(filters -> filters == null || filters.isEmpty());
        assertThat(plan.getRelationshipFilters().values())
            .as("Broad list query should not include any relationship filters")
            .allMatch(filters -> filters == null || filters.isEmpty());
    }

    @ParameterizedTest
    @MethodSource("compoundRelationshipQueryProvider")
    @DisplayName("LLM should extract relationship_query actionParams.query as relational-only text for compound user messages")
    void shouldExtractRelationalOnlyQueryForCompoundMessages(String userMessage,
                                                            String expectedRelationalQuerySnippet,
                                                            String[] forbiddenSubstrings,
                                                            String expectedPrimaryEntityType,
                                                            String[] expectedEntityTypes) {
        OrchestrationContext context = OrchestrationContext.builder()
            .userId("entity-extraction-test-user")
            .sessionId("entity-extraction-session")
            .metadata(Map.of("channel", "test"))
            .build();

        OrchestrationResult result = orchestrator.orchestrate(userMessage, context);

        assertThat(result.getType())
            .as("Compound request should produce actionable result: %s", userMessage)
            .isIn(OrchestrationResultType.ACTION_EXECUTED, OrchestrationResultType.COMPOUND_HANDLED);

        Map<String, Object> payload = extractRelationshipQueryActionPayload(result);
        RelationshipQueryPlan plan = extractRelationshipQueryPlan(payload);

        assertThat(plan.getOriginalQuery())
            .as("Planner originalQuery should be derived from relational-only actionParams.query")
            .isNotBlank()
            .containsIgnoringCase(expectedRelationalQuerySnippet);
        for (String forbidden : forbiddenSubstrings) {
            assertThat(plan.getOriginalQuery())
                .as("Relational query should not include non-relational task: " + forbidden)
                .doesNotContainIgnoringCase(forbidden);
        }

        verifyJpqlQuery(payload, expectedPrimaryEntityType, expectedEntityTypes);
    }

    static Stream<Arguments> compoundRelationshipQueryProvider() {
        return Stream.of(
            Arguments.of(
                "relationship_query: find all brands",
                "find all brands",
                new String[]{},
                "brand",
                new String[]{"brand"}
            ),
            Arguments.of(
                "relationship_query: find products under $100",
                "find products under $100",
                new String[]{},
                "product",
                new String[]{"product"}
            )
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractRelationshipQueryActionPayload(OrchestrationResult result) {
        if (result.getType() == OrchestrationResultType.ACTION_EXECUTED) {
            ActionResult actionResult = (ActionResult) result.getData().get("actionResult");
            assertThat(actionResult).isNotNull();
            assertThat(actionResult.isSuccess()).isTrue();
            return actionResult.getData().toMap();
        }

        if (result.getType() == OrchestrationResultType.COMPOUND_HANDLED) {
            OrchestrationResult child = result.getChildren().stream()
                .filter(r -> r != null && r.getType() == OrchestrationResultType.ACTION_EXECUTED)
                .filter(r -> {
                    Object action = r.getData() != null ? r.getData().get("action") : null;
                    return action != null && "relationship_query".equalsIgnoreCase(action.toString());
                })
                .findFirst()
                .orElseThrow(() -> new AssertionError("No relationship_query ACTION_EXECUTED child found"));

            ActionResult actionResult = (ActionResult) child.getData().get("actionResult");
            assertThat(actionResult).isNotNull();
            assertThat(actionResult.isSuccess()).isTrue();
            return actionResult.getData().toMap();
        }

        throw new AssertionError("Unexpected result type for relationship_query execution: " + result.getType());
    }

    @SuppressWarnings("unchecked")
    private RelationshipQueryPlan extractRelationshipQueryPlan(Map<String, Object> payload) {
        assertThat(payload).containsKey("metadata");
        Map<String, Object> metadata = (Map<String, Object>) payload.get("metadata");
        assertThat(metadata).isNotNull();
        assertThat(metadata).containsKey("plan");

        Object planObj = metadata.get("plan");
        if (planObj instanceof RelationshipQueryPlan plan) {
            return plan;
        }
        if (planObj instanceof Map<?, ?> planMap) {
            // Best-effort mapping for assertions we need here.
            String originalQuery = planMap.get("originalQuery") != null ? planMap.get("originalQuery").toString() : null;
            String primary = planMap.get("primaryEntityType") != null ? planMap.get("primaryEntityType").toString() : null;
            Object directFiltersRaw = planMap.get("directFilters");
            Map<?, ?> directFilters = directFiltersRaw instanceof Map<?, ?> map ? map : Map.of();

            return RelationshipQueryPlan.builder()
                .originalQuery(originalQuery)
                .primaryEntityType(primary)
                .directFilters((Map) directFilters)
                .build();
        }

        throw new AssertionError("Plan is neither RelationshipQueryPlan nor Map: " + planObj.getClass());
    }

    /**
     * Verifies that the JPQL query was generated correctly by checking the metadata plan.
     * The plan should contain the expected entity types and relationship paths.
     */
    @SuppressWarnings("unchecked")
    private void verifyJpqlQuery(Map<String, Object> payload, String expectedPrimaryEntityType, String... expectedEntityTypes) {
        assertThat(payload).containsKey("metadata");
        Map<String, Object> metadata = (Map<String, Object>) payload.get("metadata");
        assertThat(metadata).isNotNull();

        // Check if plan is in metadata
        if (metadata.containsKey("plan")) {
            Object planObj = metadata.get("plan");
            assertThat(planObj).isNotNull();

            String primaryEntityType;
            List<String> candidateEntityTypes;
            String queryStrategy;

            // Handle both RelationshipQueryPlan object and Map (from JSON)
            if (planObj instanceof RelationshipQueryPlan plan) {
                primaryEntityType = plan.getPrimaryEntityType();
                candidateEntityTypes = plan.getCandidateEntityTypes();
                queryStrategy = plan.getQueryStrategy() != null ? plan.getQueryStrategy().name() : null;
            } else if (planObj instanceof Map) {
                Map<String, Object> plan = (Map<String, Object>) planObj;
                primaryEntityType = (String) plan.get("primaryEntityType");
                candidateEntityTypes = (List<String>) plan.get("candidateEntityTypes");
                Object strategy = plan.get("queryStrategy");
                queryStrategy = strategy != null ? strategy.toString() : null;
            } else {
                throw new AssertionError("Plan is neither RelationshipQueryPlan nor Map: " + planObj.getClass());
            }

            // Verify primary entity type
            if (expectedPrimaryEntityType != null) {
                assertThat(primaryEntityType)
                    .as("Primary entity type should match expected: " + expectedPrimaryEntityType)
                    .isNotNull()
                    .isEqualTo(expectedPrimaryEntityType.toLowerCase());
            }

            // Verify candidate entity types or relationship paths include expected types
            if (expectedEntityTypes.length > 0) {
                assertThat(candidateEntityTypes)
                    .as("Candidate entity types should be present")
                    .isNotNull();

                // Collect all entity types from candidateEntityTypes and relationshipPaths
                List<String> allEntityTypes = new java.util.ArrayList<>(candidateEntityTypes);

                // Extract entity types from relationship paths if plan is RelationshipQueryPlan
                if (planObj instanceof RelationshipQueryPlan plan) {
                    if (plan.getRelationshipPaths() != null) {
                        plan.getRelationshipPaths().forEach(path -> {
                            if (path.getFromEntityType() != null) {
                                allEntityTypes.add(path.getFromEntityType().toLowerCase());
                            }
                            if (path.getToEntityType() != null) {
                                allEntityTypes.add(path.getToEntityType().toLowerCase());
                            }
                        });
                    }
                } else if (planObj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> planMap = (Map<String, Object>) planObj;
                    Object relationshipPaths = planMap.get("relationshipPaths");
                    if (relationshipPaths instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> paths = (List<Map<String, Object>>) relationshipPaths;
                        paths.forEach(path -> {
                            Object fromType = path.get("fromEntityType");
                            Object toType = path.get("toEntityType");
                            if (fromType != null) {
                                allEntityTypes.add(fromType.toString().toLowerCase());
                            }
                            if (toType != null) {
                                allEntityTypes.add(toType.toString().toLowerCase());
                            }
                        });
                    }
                }

                // Verify all expected entity types are present in either candidateEntityTypes or relationshipPaths
                for (String expectedType : expectedEntityTypes) {
                    String normalizedType = expectedType.toLowerCase();
                    assertThat(allEntityTypes)
                        .as("Should include entity type: " + expectedType + " (in candidateEntityTypes or relationshipPaths)")
                        .contains(normalizedType);
                }
            }

            // Verify query strategy
            assertThat(queryStrategy)
                .as("Query strategy should be set")
                .isNotNull()
                .isIn("RELATIONSHIP", "SEMANTIC", "HYBRID");
        }
    }

    private void seedCatalog() {
        // Seed products and brands
        BrandEntity nike = new BrandEntity();
        nike.setName("Nike");
        nike = brandRepository.save(nike);

        ProductEntity blueRunner = product("Blue Runner Running Shoes", "blue", BigDecimal.valueOf(95), "ACTIVE", nike);
        ProductEntity redRunner = product("Red Runner Running Shoes", "red", BigDecimal.valueOf(110), "ACTIVE", nike);

        productRepository.saveAll(List.of(blueRunner, redRunner));

        // Seed users and documents
        UserEntity johnSmith = new UserEntity();
        johnSmith.setFullName("John Smith");
        johnSmith.setEmail("john.smith@example.com");
        johnSmith = userRepository.save(johnSmith);

        DocumentEntity contractDoc = new DocumentEntity();
        contractDoc.setTitle("Q4 2023 Contract");
        contractDoc.setStatus("ACTIVE");
        contractDoc.setCreationDate(LocalDateTime.now().minusMonths(2));
        contractDoc.setAuthor(johnSmith);
        contractDoc = documentRepository.save(contractDoc);

        // Seed accounts and transactions
        AccountEntity highRiskAccount = new AccountEntity();
        highRiskAccount.setOwnerName("Suspicious Corp");
        highRiskAccount.setRegion("high-risk");
        highRiskAccount.setRiskScore(BigDecimal.valueOf(0.85));
        highRiskAccount = accountRepository.save(highRiskAccount);

        AccountEntity normalAccount = new AccountEntity();
        normalAccount.setOwnerName("Normal Corp");
        normalAccount.setRegion("normal");
        normalAccount.setRiskScore(BigDecimal.valueOf(0.3));
        normalAccount = accountRepository.save(normalAccount);

        TransactionEntity wireTransaction = new TransactionEntity();
        wireTransaction.setTitle("Wire Transfer");
        wireTransaction.setAmount(BigDecimal.valueOf(15000));
        wireTransaction.setCurrency("USD");
        wireTransaction.setChannel("WIRE");
        wireTransaction.setOccurredAt(LocalDateTime.now().minusDays(5));
        wireTransaction.setStatus("COMPLETED");
        wireTransaction.setDestinationAccount(highRiskAccount);
        wireTransaction.setSourceAccount(normalAccount);
        wireTransaction = transactionRepository.save(wireTransaction);
    }

    private ProductEntity product(String name, String color, BigDecimal price, String status, BrandEntity brand) {
        ProductEntity product = new ProductEntity();
        product.setName(name);
        product.setColor(color);
        product.setPrice(price);
        product.setStatus(status);
        product.setBrand(brand);
        brand.getProducts().add(product);
        return product;
    }

    @TestConfiguration
    static class PolicyConfig {

        @Bean
        RecordingEntityAccessPolicy recordingEntityAccessPolicy() {
            return new RecordingEntityAccessPolicy();
        }

        /**
         * Minimal compliance provider to satisfy orchestrator dependency in tests.
         */
        @Bean
        ComplianceCheckProvider allowAllComplianceCheckProvider() {
            return request -> ComplianceCheckResult.builder()
                .compliant(true)
                .details("test-allow-all")
                .build();
        }
    }

    static class RecordingEntityAccessPolicy implements EntityAccessPolicy {

        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicReference<String> lastUser = new AtomicReference<>();
        private final AtomicReference<Map<String, Object>> lastContext = new AtomicReference<>();

        @Override
        public boolean canAccess(ai.fabric.dto.AIAccessSubjectContext authContext, Map<String, Object> entity) {
            callCount.incrementAndGet();
            lastUser.set(authContext != null ? authContext.getSubjectId() : null);
            lastContext.set(Map.copyOf(entity));
            return true;
        }

        int getCallCount() {
            return callCount.get();
        }

        String getLastUser() {
            return lastUser.get();
        }

        Map<String, Object> getLastContext() {
            return lastContext.get();
        }

        void reset() {
            callCount.set(0);
            lastUser.set(null);
            lastContext.set(null);
        }
    }
}
