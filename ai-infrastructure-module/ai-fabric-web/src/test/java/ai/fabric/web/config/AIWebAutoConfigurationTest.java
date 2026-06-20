package ai.fabric.web.config;

import ai.fabric.compliance.AIComplianceService;
import ai.fabric.migration.service.DataMigrationService;
import ai.fabric.rag.service.AdvancedRAGService;
import ai.fabric.security.AISecurityService;
import ai.fabric.service.AIInfrastructureProfileService;
import ai.fabric.service.VectorManagementService;
import ai.fabric.web.controller.AIComplianceController;
import ai.fabric.web.controller.AIProfileController;
import ai.fabric.web.controller.AISecurityController;
import ai.fabric.web.controller.AdvancedRAGController;
import ai.fabric.web.migration.MigrationController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIWebAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(
            AIWebAutoConfiguration.class,
            AIWebGovernanceAutoConfiguration.class,
            AIWebMigrationAutoConfiguration.class
        ));

    @Test
    void doesNotRegisterControllersWithoutBackingServices() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(AdvancedRAGController.class);
            assertThat(context).doesNotHaveBean(AIProfileController.class);
            assertThat(context).doesNotHaveBean(AISecurityController.class);
            assertThat(context).doesNotHaveBean(AIComplianceController.class);
            assertThat(context).doesNotHaveBean(MigrationController.class);
        });
    }

    @Test
    void registersCoreControllersWhenBackingServicesExist() {
        contextRunner
            .withBean(AdvancedRAGService.class, () -> mock(AdvancedRAGService.class))
            .withBean(AIInfrastructureProfileService.class, () -> mock(AIInfrastructureProfileService.class))
            .withBean(AISecurityService.class, () -> mock(AISecurityService.class))
            .run(context -> {
                assertThat(context).hasSingleBean(AdvancedRAGController.class);
                assertThat(context).hasSingleBean(AIProfileController.class);
                assertThat(context).hasSingleBean(AISecurityController.class);
                assertThat(context).doesNotHaveBean(AIComplianceController.class);
                assertThat(context).doesNotHaveBean(MigrationController.class);
            });
    }

    @Test
    void advancedRAGControllerReceivesOptionalVectorDiagnosticsProvider() {
        VectorManagementService vectorManagementService = mock(VectorManagementService.class);
        when(vectorManagementService.getProviderDiagnostics()).thenReturn(Map.of(
            "diagnosticsAvailable", true,
            "providerClass", "test-vector-provider",
            "supportsScanMetadataFiltering", true
        ));

        contextRunner
            .withBean(AdvancedRAGService.class, () -> mock(AdvancedRAGService.class))
            .withBean(VectorManagementService.class, () -> vectorManagementService)
            .run(context -> {
                AdvancedRAGController controller = context.getBean(AdvancedRAGController.class);
                Map<String, Object> health = controller.healthCheck().getBody();

                assertThat(health).isNotNull();
                assertThat(health.get("vectorDatabase"))
                    .isInstanceOfSatisfying(Map.class, diagnostics -> assertThat(diagnostics)
                        .containsEntry("available", true)
                        .containsEntry("diagnosticsAvailable", true)
                        .containsEntry("providerClass", "test-vector-provider")
                        .containsEntry("supportsScanMetadataFiltering", true));
            });
    }

    @Test
    void registersOptionalControllersWhenOptionalServicesExist() {
        contextRunner
            .withBean(AIComplianceService.class, () -> mock(AIComplianceService.class))
            .withBean(DataMigrationService.class, () -> mock(DataMigrationService.class))
            .run(context -> {
                assertThat(context).hasSingleBean(AIComplianceController.class);
                assertThat(context).hasSingleBean(MigrationController.class);
            });
    }

    @Test
    void backsOffWhenApplicationDefinesControllerBean() {
        DataMigrationService migrationService = mock(DataMigrationService.class);

        contextRunner
            .withBean(DataMigrationService.class, () -> migrationService)
            .withBean(MigrationController.class, () -> new MigrationController(migrationService))
            .run(context -> assertThat(context).hasSingleBean(MigrationController.class));
    }

    @Test
    void controllerTogglesDisableIndividualControllers() {
        contextRunner
            .withBean(AISecurityService.class, () -> mock(AISecurityService.class))
            .withPropertyValues("ai.web.controllers.security=false")
            .run(context -> assertThat(context).doesNotHaveBean(AISecurityController.class));
    }

    @Test
    void disabledWebAutoConfigurationDoesNotRegisterControllers() {
        contextRunner
            .withBean(AdvancedRAGService.class, () -> mock(AdvancedRAGService.class))
            .withBean(AISecurityService.class, () -> mock(AISecurityService.class))
            .withPropertyValues("ai.web.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(AdvancedRAGController.class);
                assertThat(context).doesNotHaveBean(AISecurityController.class);
            });
    }
}
