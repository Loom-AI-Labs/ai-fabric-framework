package ai.fabric.it;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Bean;

/**
 * Test Application for AI Infrastructure Integration Tests
 *
 * This is a minimal Spring Boot application used to test the AI Infrastructure
 * module in isolation. It provides a clean environment for integration testing
 * without dependencies on the main backend application.
 *
 * @author AI Infrastructure Team
 * @version 1.0.0
 */
@SpringBootApplication
@ComponentScan(
    // IMPORTANT: Do not scan the entire `ai.fabric.it` package.
    // Test sources are on the classpath during `mvn test`, and broad scanning can
    // accidentally pick up nested `@TestConfiguration` classes from unrelated tests,
    // overriding beans (e.g., AvailableActionsRegistry / AIActionRegistry) and
    // breaking other integration tests (provider-matrix included).
    basePackages = {
        "ai.fabric.it.config",
        "ai.fabric.it.entity",
        "ai.fabric.it.repository",
        "ai.fabric.it.service"
    }
)
public class TestApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestApplication.class, args);
    }

    @Bean
    public EntityAccessPolicy testEntityAccessPolicy() {
        return (userId, entity) -> true;
    }

    @Bean
    public ComplianceCheckProvider testComplianceCheckProvider() {
        return request -> ComplianceCheckResult.builder()
            .compliant(true)
            .details("Test compliance provider approval")
            .build();
    }
}
