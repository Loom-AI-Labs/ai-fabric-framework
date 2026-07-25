package ai.fabric.it;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.dto.AIEntityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("real-api-test")
public class ConfigurationTest {

    @Autowired
    private AIEntityConfigurationLoader configurationLoader;

    @Test
    public void testConfigurationLoading() {
        // Test that configuration is loaded
        assertNotNull(configurationLoader);

        // Test that test-product configuration exists
        AIEntityConfig testProductConfig = configurationLoader.getEntityConfig("test-product");
        assertNotNull(testProductConfig, "test-product configuration should exist");

        // Test that metadata fields are loaded
        assertNotNull(testProductConfig.getMetadataFields(), "metadata fields should not be null");
        assertFalse(testProductConfig.getMetadataFields().isEmpty(), "metadata fields should not be empty");

        System.out.println("Metadata fields count: " + testProductConfig.getMetadataFields().size());
        testProductConfig.getMetadataFields().forEach(field ->
            System.out.println("Field: " + field.getName() + " Type: " + field.getDataType())
        );
    }
}
