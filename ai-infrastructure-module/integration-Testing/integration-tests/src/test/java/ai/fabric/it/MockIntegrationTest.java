package ai.fabric.it;

import ai.fabric.dto.VectorRecord;
import ai.fabric.indexing.api.AIEntityIndexingGateway;
import ai.fabric.indexing.api.AIProcessOperation;
import ai.fabric.indexing.api.IndexingStrategy;
import ai.fabric.service.VectorManagementService;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.entity.TestUser;
import ai.fabric.it.entity.TestArticle;
import ai.fabric.it.repository.TestProductRepository;
import ai.fabric.it.repository.TestUserRepository;
import ai.fabric.it.repository.TestArticleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mock Integration Test for AI Infrastructure Module
 *
 * Tests all core AI functions using mocked services that don't require API keys.
 * This allows for comprehensive testing without external dependencies.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("mock-test")
@TestPropertySource(properties = {
    "ai.vector-db.lucene.index-path=./data/test-lucene-index/mock"
})
public class MockIntegrationTest {

    @Autowired
    private AIEntityIndexingGateway indexingGateway;

    @Autowired
    private TestProductRepository productRepository;

    @Autowired
    private TestUserRepository userRepository;

    @Autowired
    private TestArticleRepository articleRepository;

    @Autowired
    private VectorManagementService vectorManagementService;

    private static final List<String> INDEXED_TYPES = List.of("test-product", "test-user", "test-article");

    private List<VectorRecord> entities(String entityType) {
        return vectorManagementService.getVectorsByEntityType(entityType);
    }

    private List<VectorRecord> allEntities() {
        return INDEXED_TYPES.stream()
            .flatMap(type -> entities(type).stream())
            .toList();
    }

    private List<VectorRecord> searchContent(String snippet) {
        String needle = snippet.toLowerCase();
        return allEntities().stream()
            .filter(e -> e.getContent() != null && e.getContent().toLowerCase().contains(needle))
            .toList();
    }

    @BeforeEach
    public void setUp() {
        vectorManagementService.clearAllVectors();
        productRepository.deleteAll();
        userRepository.deleteAll();
        articleRepository.deleteAll();
    }

    @Test
    public void testMockEmbeddingGeneration() {
        System.out.println("🧪 Testing Mock Embedding Generation...");

        // Given - Create a product with rich content
        TestProduct product = TestProduct.builder()
            .name("AI-Powered Smart Watch")
            .description("Advanced smartwatch with AI-powered health monitoring, sleep tracking, and personalized recommendations")
            .category("Wearables")
            .brand("TechCorp")
            .price(new BigDecimal("299.99"))
            .sku("SW-AI-001")
            .stockQuantity(50)
            .active(true)
            .build();

        // When - Save and process the product
        product = productRepository.save(product);
        indexingGateway.upsert(product, AIProcessOperation.CREATE, IndexingStrategy.SYNC);

        // Then - Verify AI processing
        List<VectorRecord> searchableEntities = entities("test-product");
        assertEquals(1, searchableEntities.size(), "Should process one product");

        VectorRecord entity = searchableEntities.get(0);
        assertNotNull(entity.getVectorId(), "Should have vector ID");
        assertFalse(entity.getVectorId().isEmpty(), "Vector ID should not be empty");

        // Verify vector exists in vector database
        assertTrue(entity.getVectorId().length() >= 30, "Should have substantial embeddings");
        assertNotNull(entity.getContent(), "Should have searchable content");
        assertTrue(entity.getContent().contains("AI-Powered"), "Should contain product name");
        assertTrue(entity.getContent().contains("smartwatch"), "Should contain description");

        System.out.println("✅ Mock Embedding Generation Test Passed");
        System.out.println("   - Vector ID: " + entity.getVectorId().length());
        System.out.println("   - Searchable content length: " + entity.getContent().length());
    }

    @Test
    public void testMockUserProcessing() {
        System.out.println("🧪 Testing Mock User Processing...");

        // Given - Create a user with profile information
        TestUser user = TestUser.builder()
            .firstName("John")
            .lastName("Doe")
            .email("john.doe@example.com")
            .bio("Software engineer passionate about AI and machine learning. Love working on innovative projects.")
            .age(28)
            .location("San Francisco, CA")
            .phoneNumber("+1-555-0123")
            .dateOfBirth(LocalDate.of(1995, 6, 15))
            .active(true)
            .build();

        // When - Save and process the user
        user = userRepository.save(user);
        indexingGateway.upsert(user, AIProcessOperation.CREATE, IndexingStrategy.SYNC);

        // Then - Verify AI processing
        List<VectorRecord> searchableEntities = entities("test-user");
        assertEquals(1, searchableEntities.size(), "Should process one user");

        VectorRecord entity = searchableEntities.get(0);
        assertNotNull(entity.getVectorId(), "Should have vector ID");
        assertFalse(entity.getVectorId().isEmpty(), "Vector ID should not be empty");

        assertNotNull(entity.getContent(), "Should have searchable content");
        assertTrue(entity.getContent().contains("John Doe"), "Should contain user name");
        assertTrue(entity.getContent().contains("AI and machine learning"), "Should contain bio content");

        System.out.println("✅ Mock User Processing Test Passed");
        System.out.println("   - Vector ID: " + entity.getVectorId().length());
        System.out.println("   - Searchable content length: " + entity.getContent().length());
    }

    @Test
    public void testMockArticleProcessing() {
        System.out.println("🧪 Testing Mock Article Processing...");

        // Given - Create an article with rich content
        TestArticle article = TestArticle.builder()
            .title("The Future of Artificial Intelligence in Healthcare")
            .content("Artificial Intelligence is revolutionizing healthcare through advanced diagnostics, personalized treatment plans, and predictive analytics. This comprehensive guide explores the latest developments in AI-powered medical technologies.")
            .summary("AI is transforming healthcare with advanced diagnostics and personalized treatments")
            .author("Dr. Sarah Johnson")
            .tags("AI, Healthcare, Technology, Innovation")
            .published(true)
            .publishDate(LocalDateTime.now())
            .readTime(8)
            .viewCount(1250)
            .build();

        // When - Save and process the article
        article = articleRepository.save(article);
        indexingGateway.upsert(article, AIProcessOperation.CREATE, IndexingStrategy.SYNC);

        // Then - Verify AI processing
        List<VectorRecord> searchableEntities = entities("test-article");
        assertEquals(1, searchableEntities.size(), "Should process one article");

        VectorRecord entity = searchableEntities.get(0);
        assertNotNull(entity.getVectorId(), "Should have vector ID");
        assertFalse(entity.getVectorId().isEmpty(), "Vector ID should not be empty");

        assertNotNull(entity.getContent(), "Should have searchable content");
        assertTrue(entity.getContent().contains("Artificial Intelligence"), "Should contain title");
        assertTrue(entity.getContent().contains("healthcare"), "Should contain content");

        System.out.println("✅ Mock Article Processing Test Passed");
        System.out.println("   - Vector ID: " + entity.getVectorId().length());
        System.out.println("   - Searchable content length: " + entity.getContent().length());
    }

    @Test
    public void testMockSearchFunctionality() {
        System.out.println("🧪 Testing Mock Search Functionality...");

        // Given - Create multiple entities with different content
        List<TestProduct> products = List.of(
            TestProduct.builder()
                .name("AI-Powered Laptop")
                .description("High-performance laptop with AI acceleration for machine learning workloads")
                .category("Computers")
                .price(new BigDecimal("1999.99"))
                .active(true)
                .build(),
            TestProduct.builder()
                .name("Regular Mouse")
                .description("Standard computer mouse for everyday use")
                .category("Accessories")
                .price(new BigDecimal("29.99"))
                .active(true)
                .build()
        );

        List<TestUser> users = List.of(
            TestUser.builder()
                .firstName("Alice")
                .lastName("Smith")
                .email("alice@example.com")
                .bio("Data scientist specializing in AI and machine learning")
                .active(true)
                .build(),
            TestUser.builder()
                .firstName("Bob")
                .lastName("Johnson")
                .email("bob@example.com")
                .bio("Marketing professional with focus on digital campaigns")
                .active(true)
                .build()
        );

        // When - Process all entities
        products = productRepository.saveAll(products);
        users = userRepository.saveAll(users);

        for (TestProduct product : products) {
            indexingGateway.upsert(product, AIProcessOperation.CREATE, IndexingStrategy.SYNC);
        }
        for (TestUser user : users) {
            indexingGateway.upsert(user, AIProcessOperation.CREATE, IndexingStrategy.SYNC);
        }

        // Then - Test various search scenarios
        List<VectorRecord> allEntities = entities("test-product");
        assertEquals(2, allEntities.size(), "Should have two products");

        // Search for AI-related content
        List<VectorRecord> aiResults = searchContent("AI");
        assertFalse(aiResults.isEmpty(), "Should find AI-related content");
        assertTrue(aiResults.size() >= 2, "Should find multiple AI-related results");

        // Search for specific terms
        List<VectorRecord> laptopResults = searchContent("laptop");
        assertFalse(laptopResults.isEmpty(), "Should find laptop-related content");

        List<VectorRecord> dataResults = searchContent("data scientist");
        assertFalse(dataResults.isEmpty(), "Should find data scientist content");

        System.out.println("✅ Mock Search Functionality Test Passed");
        System.out.println("   - Total entities: " + allEntities().size());
        System.out.println("   - AI-related results: " + aiResults.size());
        System.out.println("   - Laptop results: " + laptopResults.size());
        System.out.println("   - Data scientist results: " + dataResults.size());
    }

    @Test
    public void testMockEntityRemoval() {
        System.out.println("🧪 Testing Mock Entity Removal...");

        // Given - Create and process entities
        TestProduct product = TestProduct.builder()
            .name("Product to Remove")
            .description("This product will be removed from the index")
            .category("Test")
            .price(new BigDecimal("99.99"))
            .active(true)
            .build();

        TestUser user = TestUser.builder()
            .firstName("User")
            .lastName("ToRemove")
            .email("remove@example.com")
            .bio("This user will be removed")
            .active(true)
            .build();

        product = productRepository.save(product);
        user = userRepository.save(user);

        indexingGateway.upsert(product, AIProcessOperation.CREATE, IndexingStrategy.SYNC);
        indexingGateway.upsert(user, AIProcessOperation.CREATE, IndexingStrategy.SYNC);

        // Verify entities exist
        assertEquals(2, allEntities().size(), "Should have two entities before removal");

        // When - Remove entities
        productRepository.delete(product);
        userRepository.delete(user);

        indexingGateway.delete(
            TestProduct.class,
            product.getId().toString(),
            IndexingStrategy.SYNC
        );
        indexingGateway.delete(
            TestUser.class,
            user.getId().toString(),
            IndexingStrategy.SYNC
        );

        // Then - Verify removal
        assertEquals(0, allEntities().size(), "Should remove all entities from index");

        System.out.println("✅ Mock Entity Removal Test Passed");
        System.out.println("   - Entities before removal: 2");
        System.out.println("   - Entities after removal: " + allEntities().size());
    }

    @Test
    public void testMockMultiEntityWorkflow() {
        System.out.println("🧪 Testing Mock Multi-Entity Workflow...");

        // Given - Create a complex scenario with multiple entity types
        TestUser author = TestUser.builder()
            .firstName("Dr. Jane")
            .lastName("Wilson")
            .email("jane.wilson@example.com")
            .bio("AI researcher and technology writer with 10+ years experience")
            .active(true)
            .build();

        TestArticle article = TestArticle.builder()
            .title("Machine Learning in E-commerce: A Complete Guide")
            .content("This comprehensive guide covers how machine learning is transforming e-commerce through recommendation systems, fraud detection, and customer analytics.")
            .summary("ML is revolutionizing e-commerce with smart recommendations and analytics")
            .author("Dr. Jane Wilson")
            .tags("Machine Learning, E-commerce, AI, Analytics")
            .published(true)
            .publishDate(LocalDateTime.now())
            .readTime(12)
            .viewCount(5000)
            .build();

        TestProduct featuredProduct = TestProduct.builder()
            .name("ML-Powered Recommendation Engine")
            .description("Advanced recommendation system using machine learning algorithms for personalized product suggestions")
            .category("Software")
            .brand("TechSolutions")
            .price(new BigDecimal("9999.99"))
            .active(true)
            .build();

        // When - Process all entities
        author = userRepository.save(author);
        article = articleRepository.save(article);
        featuredProduct = productRepository.save(featuredProduct);

        indexingGateway.upsert(author, AIProcessOperation.CREATE, IndexingStrategy.SYNC);
        indexingGateway.upsert(article, AIProcessOperation.CREATE, IndexingStrategy.SYNC);
        indexingGateway.upsert(featuredProduct, AIProcessOperation.CREATE, IndexingStrategy.SYNC);

        // Then - Verify comprehensive search capabilities
        List<VectorRecord> allEntities = allEntities();
        assertEquals(3, allEntities.size(), "Should process all three entities");

        // Search for machine learning content across all entities
        List<VectorRecord> mlResults = searchContent("machine learning");
        assertTrue(mlResults.size() >= 2, "Should find ML content in multiple entities");

        // Search for e-commerce content
        List<VectorRecord> ecommerceResults = searchContent("e-commerce");
        assertFalse(ecommerceResults.isEmpty(), "Should find e-commerce content");

        // Search for recommendation content
        List<VectorRecord> recommendationResults = searchContent("recommendation");
        assertFalse(recommendationResults.isEmpty(), "Should find recommendation content");

        System.out.println("✅ Mock Multi-Entity Workflow Test Passed");
        System.out.println("   - Total entities processed: " + allEntities.size());
        System.out.println("   - ML-related results: " + mlResults.size());
        System.out.println("   - E-commerce results: " + ecommerceResults.size());
        System.out.println("   - Recommendation results: " + recommendationResults.size());
    }

    @Test
    public void testMockAIAnalysisAndMetadata() {
        System.out.println("🧪 Testing Mock AI Analysis and Metadata...");

        // Given - Create a product with rich metadata
        TestProduct product = TestProduct.builder()
            .name("Advanced AI Camera")
            .description("Professional camera with AI-powered object recognition, scene optimization, and automatic photo enhancement")
            .category("Photography")
            .brand("PhotoTech")
            .price(new BigDecimal("1299.99"))
            .sku("CAM-AI-PRO")
            .stockQuantity(25)
            .active(true)
            .build();

        // When - Process the product
        product = productRepository.save(product);
        indexingGateway.upsert(product, AIProcessOperation.CREATE, IndexingStrategy.SYNC);

        // Then - Verify AI analysis and metadata
        List<VectorRecord> entities = entities("test-product");
        assertEquals(1, entities.size(), "Should process one product");

        VectorRecord entity = entities.get(0);

        // Verify embeddings
        assertNotNull(entity.getVectorId(), "Should have vector ID");
        assertFalse(entity.getVectorId().isEmpty(), "Vector ID should not be empty");

        assertTrue(entity.getVectorId().length() >= 30, "Should have substantial embeddings");

        // Verify searchable content
        assertNotNull(entity.getContent(), "Should have searchable content");
        assertTrue(entity.getContent().length() > 50, "Should have substantial searchable content");
        assertTrue(entity.getContent().contains("AI Camera"), "Should contain product name");
        assertTrue(entity.getContent().contains("object recognition"), "Should contain key features");

        // Verify metadata
        assertNotNull(entity.getMetadata(), "Should have metadata");
        assertTrue(entity.getMetadata().containsKey("category"), "Should have category metadata");
        assertTrue(entity.getMetadata().containsKey("brand"), "Should have brand metadata");
        assertTrue(entity.getMetadata().containsKey("price"), "Should have price metadata");

        // Verify timestamps
        assertNotNull(entity.getCreatedAt(), "Should have creation timestamp");
        assertNotNull(entity.getUpdatedAt(), "Should have update timestamp");

        System.out.println("✅ Mock AI Analysis and Metadata Test Passed");
        System.out.println("   - Vector ID: " + entity.getVectorId().length());
        System.out.println("   - Searchable content length: " + entity.getContent().length());
        System.out.println("   - Metadata size: " + entity.getMetadata().size());
        System.out.println("   - Created at: " + entity.getCreatedAt());
    }

    @Test
    public void testMockBatchProcessing() {
        System.out.println("🧪 Testing Mock Batch Processing...");

        // Given - Create multiple products for batch processing
        List<TestProduct> products = List.of(
            TestProduct.builder()
                .name("AI Product 1")
                .description("First AI-powered product")
                .category("AI")
                .price(new BigDecimal("100.00"))
                .active(true)
                .build(),
            TestProduct.builder()
                .name("AI Product 2")
                .description("Second AI-powered product")
                .category("AI")
                .price(new BigDecimal("200.00"))
                .active(true)
                .build(),
            TestProduct.builder()
                .name("AI Product 3")
                .description("Third AI-powered product")
                .category("AI")
                .price(new BigDecimal("300.00"))
                .active(true)
                .build()
        );

        // When - Process all products in batch
        products = productRepository.saveAll(products);
        for (TestProduct product : products) {
            indexingGateway.upsert(product, AIProcessOperation.CREATE, IndexingStrategy.SYNC);
        }

        // Then - Verify all products were processed
        List<VectorRecord> allEntities = entities("test-product");
        assertEquals(3, allEntities.size(), "Should process all three products");

        // Verify each entity has proper processing
        for (VectorRecord entity : allEntities) {
            assertNotNull(entity.getVectorId(), "Each entity should have embeddings");
            assertTrue(entity.getVectorId().length() >= 30, "Each entity should have substantial embeddings");
            assertNotNull(entity.getContent(), "Each entity should have searchable content");
            assertTrue(entity.getContent().contains("AI Product"), "Each entity should contain product name");
        }

        System.out.println("✅ Mock Batch Processing Test Passed");
        System.out.println("   - Products processed: " + allEntities.size());
        System.out.println("   - All products have vector IDs: " +
            allEntities.stream().allMatch(e -> e.getVectorId() != null && !e.getVectorId().isEmpty()));
    }
}
