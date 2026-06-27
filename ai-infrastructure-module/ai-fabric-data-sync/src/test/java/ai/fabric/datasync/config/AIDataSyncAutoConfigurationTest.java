package ai.fabric.datasync.config;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.datasync.AIDataSyncProperties;
import ai.fabric.datasync.controller.DataSyncController;
import ai.fabric.datasync.normalize.DataSyncEntityNormalizer;
import ai.fabric.datasync.service.DataSyncService;
import ai.fabric.service.VectorManagementService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AIDataSyncAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AIDataSyncAutoConfiguration.class))
        .withBean(AIEntityConfigurationLoader.class, () -> mock(AIEntityConfigurationLoader.class))
        .withBean(AIEmbeddingService.class, () -> mock(AIEmbeddingService.class))
        .withBean(VectorManagementService.class, () -> mock(VectorManagementService.class))
        .withBean(AIAccessControlService.class, () -> mock(AIAccessControlService.class));

    @Test
    void remainsDisabledByDefault() {
        contextRunner
            .withPropertyValues("ai.vector-db.type=memory")
            .run(context -> {
                assertThat(context).doesNotHaveBean(DataSyncService.class);
                assertThat(context).doesNotHaveBean(DataSyncController.class);
            });
    }

    @Test
    void createsDataSyncBeansWhenEnabledAndVectorStoreConfigured() {
        contextRunner
            .withPropertyValues(
                "ai.data-sync.enabled=true",
                "ai.vector-db.type=memory"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(AIDataSyncProperties.class);
                assertThat(context).hasSingleBean(DataSyncEntityNormalizer.class);
                assertThat(context).hasSingleBean(DataSyncService.class);
                assertThat(context).hasSingleBean(DataSyncController.class);
            });
    }

    @Test
    void createsServiceWithoutRequiringApplicationClockBean() {
        contextRunner
            .withPropertyValues(
                "ai.data-sync.enabled=true",
                "ai.vector-db.type=memory"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(Clock.class);
                assertThat(context).hasSingleBean(DataSyncService.class);
            });
    }

    @Test
    void trustedPlatformInternalSyncBypassIsDisabledByDefault() {
        contextRunner
            .withPropertyValues(
                "ai.data-sync.enabled=true",
                "ai.vector-db.type=memory"
            )
            .run(context -> assertThat(context.getBean(AIDataSyncProperties.class)
                .isAllowTrustedPlatformInternalSyncBypass()).isFalse());
    }

    @Test
    void backsOffWhenApplicationProvidesServiceAndControllerBeans() {
        DataSyncService service = mock(DataSyncService.class);
        DataSyncController controller = new DataSyncController(service);

        contextRunner
            .withBean(DataSyncService.class, () -> service)
            .withBean(DataSyncController.class, () -> controller)
            .withPropertyValues(
                "ai.data-sync.enabled=true",
                "ai.vector-db.type=memory"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(DataSyncService.class);
                assertThat(context).hasSingleBean(DataSyncController.class);
                assertThat(context.getBean(DataSyncService.class)).isSameAs(service);
                assertThat(context.getBean(DataSyncController.class)).isSameAs(controller);
            });
    }

    @Test
    void doesNotCreateBeansWhenEmbeddingsAreDisabled() {
        contextRunner
            .withPropertyValues(
                "ai.data-sync.enabled=true",
                "ai.service.features.enable-embeddings=false",
                "ai.vector-db.type=memory"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(DataSyncService.class);
                assertThat(context).doesNotHaveBean(DataSyncController.class);
            });
    }
}
