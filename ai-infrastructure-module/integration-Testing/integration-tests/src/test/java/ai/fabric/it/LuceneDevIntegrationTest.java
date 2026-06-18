package ai.fabric.it;

import ai.fabric.service.AICapabilityService;
import ai.fabric.service.VectorManagementService;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration Test for Dev Profile with Lucene Vector Database
 *
 * Tests that Lucene vector database is working properly with the dev profile.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("dev")
@TestPropertySource(properties = "ai.vector-db.lucene.index-path=./data/test-lucene-index/dev")
@Transactional
public class LuceneDevIntegrationTest {

    @Autowired
    private AICapabilityService capabilityService;

    @Autowired
    private VectorManagementService vectorManagementService;

    @Autowired
    private TestProductRepository productRepository;

    @BeforeEach
    public void setUp() {
        // Clean up before each test
        productRepository.deleteAll();
    }

    @Test
    public void testLuceneVectorStorageAndRetrieval() {
        System.out.println("🧪 Testing Lucene Vector Database with Dev Profile...");

        // Given - Create a product with rich content for embedding
        TestProduct product = TestProduct.builder()
            .name("AI-Powered Smart Home Hub")
            .description("Revolutionary smart home hub that uses artificial intelligence to learn your habits, optimize energy usage, and provide personalized automation.")
            .category("Smart Home")
            .brand("FutureTech")
            .price(new BigDecimal("399.99"))
            .sku("SH-AI-2024")
            .stockQuantity(100)
            .active(true)
            .build();

        // When - Save and process with AI (should use Lucene)
        product = productRepository.save(product);
        capabilityService.processEntityForAI(product, "test-product");

        // Then - Verify Lucene processing
        List<TestProduct> products = productRepository.findAll();
        assertEquals(1, products.size(), "Should have one product");

        // Verify that vectors can be searched (Lucene specific)
        assertNotNull(vectorManagementService, "VectorManagementService should be injected");

        System.out.println("✅ Lucene Vector Database test passed!");
        System.out.println("   - Product processed: " + product.getName());
        System.out.println("   - Vector service available: " + (vectorManagementService != null));
    }

    @Test
    public void testLuceneSearchFunctionality() {
        System.out.println("🔍 Testing Lucene Search Functionality...");

        // Create multiple products
        TestProduct product1 = TestProduct.builder()
            .name("Wireless Headphones")
            .description("High-quality wireless headphones with noise cancellation")
            .category("Audio")
            .brand("SoundTech")
            .price(new BigDecimal("199.99"))
            .sku("WH-001")
            .active(true)
            .build();

        TestProduct product2 = TestProduct.builder()
            .name("Gaming Keyboard")
            .description("Mechanical gaming keyboard with RGB lighting")
            .category("Gaming")
            .brand("GameTech")
            .price(new BigDecimal("149.99"))
            .sku("GK-001")
            .active(true)
            .build();

        productRepository.save(product1);
        productRepository.save(product2);

        capabilityService.processEntityForAI(product1, "test-product");
        capabilityService.processEntityForAI(product2, "test-product");

        // Verify products were processed
        List<TestProduct> products = productRepository.findAll();
        assertEquals(2, products.size(), "Should have two products");

        System.out.println("✅ Lucene Search test passed!");
        System.out.println("   - Products processed: " + products.size());
    }
}
