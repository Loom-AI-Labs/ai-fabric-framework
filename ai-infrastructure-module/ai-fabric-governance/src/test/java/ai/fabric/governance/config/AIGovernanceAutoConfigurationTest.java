package ai.fabric.governance.config;

import ai.fabric.governance.catalog.IndexCatalog;
import ai.fabric.governance.catalog.disabled.DisabledIndexCatalog;
import ai.fabric.governance.catalog.jpa.IndexCatalogRepository;
import ai.fabric.governance.catalog.jpa.JpaIndexCatalog;
import ai.fabric.governance.catalog.vector.VectorIndexCatalog;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AIGovernanceAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AIGovernanceAutoConfiguration.class));

    @Test
    void doesNotCreateGovernanceBeansWhenDisabled() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(IndexCatalog.class);
            assertThat(context).doesNotHaveBean("governanceObjectMapper");
        });
    }

    @Test
    void sqlModeCreatesJpaCatalogWithoutVectorDatabaseService() {
        IndexCatalogRepository repository = mock(IndexCatalogRepository.class);

        contextRunner
            .withBean(IndexCatalogRepository.class, () -> repository)
            .withPropertyValues(
                "ai.governance.enabled=true",
                "ai.governance.catalog.mode=sql"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(IndexCatalog.class);
                assertThat(context.getBean(IndexCatalog.class)).isInstanceOf(JpaIndexCatalog.class);
            });
    }

    @Test
    void autoModeFallsBackToSqlCatalogWhenVectorDatabaseServiceIsMissing() {
        IndexCatalogRepository repository = mock(IndexCatalogRepository.class);

        contextRunner
            .withBean(IndexCatalogRepository.class, () -> repository)
            .withPropertyValues("ai.governance.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(IndexCatalog.class);
                assertThat(context.getBean(IndexCatalog.class)).isInstanceOf(JpaIndexCatalog.class);
            });
    }

    @Test
    void autoModeUsesDisabledCatalogWhenNoDurableBackendIsAvailable() {
        contextRunner
            .withPropertyValues("ai.governance.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(IndexCatalog.class);
                assertThat(context.getBean(IndexCatalog.class)).isInstanceOf(DisabledIndexCatalog.class);
            });
    }

    @Test
    void autoModePrefersVectorCatalogWhenVectorScanAndMetadataFilteringAreSupported() {
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        when(vectorDatabaseService.supportsVectorScan()).thenReturn(true);
        when(vectorDatabaseService.supportsScanMetadataFiltering()).thenReturn(true);

        contextRunner
            .withBean(VectorDatabaseService.class, () -> vectorDatabaseService)
            .withPropertyValues("ai.governance.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(IndexCatalog.class);
                assertThat(context.getBean(IndexCatalog.class)).isInstanceOf(VectorIndexCatalog.class);
            });
    }

    @Test
    void autoModeDoesNotUseVectorCatalogWhenOnlySearchMetadataFilteringIsSupported() {
        IndexCatalogRepository repository = mock(IndexCatalogRepository.class);
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        when(vectorDatabaseService.supportsVectorScan()).thenReturn(true);
        when(vectorDatabaseService.supportsSearchMetadataFiltering()).thenReturn(true);
        when(vectorDatabaseService.supportsScanMetadataFiltering()).thenReturn(false);

        contextRunner
            .withBean(VectorDatabaseService.class, () -> vectorDatabaseService)
            .withBean(IndexCatalogRepository.class, () -> repository)
            .withPropertyValues("ai.governance.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(IndexCatalog.class);
                assertThat(context.getBean(IndexCatalog.class)).isInstanceOf(JpaIndexCatalog.class);
            });
    }

    @Test
    void vectorModeFailsFastWhenVectorDatabaseServiceIsMissing() {
        contextRunner
            .withPropertyValues(
                "ai.governance.enabled=true",
                "ai.governance.catalog.mode=vector"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("VectorDatabaseService");
            });
    }

    @Test
    void autoConfigurationImportsRegistersGovernanceAutoConfigurations() throws Exception {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports")) {
            assertThat(input).isNotNull();
            String imports = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(imports)
                .contains(AIGovernanceAutoConfigurationPackages.class.getName())
                .contains(AIGovernanceAutoConfiguration.class.getName());
            assertThat(AIGovernanceAutoConfigurationPackages.BASE_PACKAGE).isEqualTo("ai.fabric.governance");
        }
    }
}
