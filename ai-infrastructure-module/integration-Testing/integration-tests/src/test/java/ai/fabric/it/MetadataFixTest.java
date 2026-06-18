package ai.fabric.it;

import ai.fabric.service.AICapabilityService;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import ai.fabric.it.entity.TestProduct;
import ai.fabric.it.repository.TestProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
@Disabled("Skipped due to Hibernate lazy table creation issue with @Transactional")
public class MetadataFixTest {

    private static final Logger log = LoggerFactory.getLogger(MetadataFixTest.class);

    @Autowired
    private AICapabilityService capabilityService;

    @Autowired
    private AIEntityConfigurationLoader configurationLoader;

    @Autowired
    private TestProductRepository productRepository;

    @BeforeEach
    @Transactional
    public void setUp() {
        // Clean up before each test
        // Hibernate will create tables on first save if they don't exist
        try {
            productRepository.deleteAll();
        } catch (Exception e) {
            // If tables don't exist, that's okay - they'll be created by Hibernate
            log.debug("Tables may not exist yet, will be created by Hibernate", e);
        }
    }

    @Test
    @Transactional
    public void testConfigurationLoaderHasMetadataFields() {
        // Test that configuration loader properly loads metadata fields
        AIEntityConfig config = configurationLoader.getEntityConfig("test-product");
        assertNotNull(config, "Configuration should not be null");
        assertNotNull(config.getMetadataFields(), "Metadata fields should not be null");
        assertFalse(config.getMetadataFields().isEmpty(), "Metadata fields should not be empty");

        System.out.println("Metadata fields count: " + config.getMetadataFields().size());
        config.getMetadataFields().forEach(field ->
            System.out.println("Field: " + field.getName() + " Type: " + field.getType())
        );
    }

    @Test
    @Transactional
    public void testProcessEntityForAIWithMetadata() {
        // Create and save a test product entity
        // Hibernate will create the table automatically on first save
        TestProduct savedProduct = productRepository.save(TestProduct.builder()
            .name("Test Product")
            .description("Test Description")
            .category("Electronics")
            .price(new BigDecimal("99.99"))
            .brand("TestBrand")
            .active(true)
            .build());

        // This should not throw a NullPointerException or transaction rollback exception
        final TestProduct product = savedProduct;
        assertDoesNotThrow(() -> {
            capabilityService.processEntityForAI(product, "test-product");
        }, "processEntityForAI should not throw NullPointerException for metadata fields");
    }
}
