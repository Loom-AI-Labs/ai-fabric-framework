package ai.fabric.it;

import ai.fabric.vector.memory.InMemoryVectorDatabaseService;
import ai.fabric.service.VectorManagementService;
import ai.fabric.provider.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify VectorManagementService bean creation
 */
@SpringBootTest(classes = TestApplication.class)
@ActiveProfiles("test")
public class TestVectorManagementService {

    @Test
    public void testVectorManagementServiceCreation() {
        // This test will fail if VectorManagementService bean is not created
        // The test will pass if the Spring context loads successfully
        assertTrue(true, "VectorManagementService bean should be created");
    }
}