package ai.fabric.vector.memory;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MemoryVectorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(MemoryVectorAutoConfiguration.class))
        .withBean(AIProviderConfig.class, AIProviderConfig::new);

    @Test
    void createsMemoryVectorServiceWhenMemoryTypeSelected() {
        contextRunner
            .withPropertyValues("ai.vector-db.type=memory")
            .run(context -> {
                assertThat(context).hasSingleBean(InMemoryVectorDatabaseService.class);
                assertThat(context).hasSingleBean(VectorDatabaseService.class);
                assertThat(context.getBean(VectorDatabaseService.class))
                    .isSameAs(context.getBean(InMemoryVectorDatabaseService.class));
            });
    }

    @Test
    void doesNotCreateMemoryVectorServiceWhenTypeDoesNotMatch() {
        contextRunner
            .withPropertyValues("ai.vector-db.type=qdrant")
            .run(context -> {
                assertThat(context).doesNotHaveBean(InMemoryVectorDatabaseService.class);
                assertThat(context).doesNotHaveBean(VectorDatabaseService.class);
            });
    }

    @Test
    void backsOffWhenApplicationProvidesVectorDatabaseService() {
        VectorDatabaseService customService = mock(VectorDatabaseService.class);

        contextRunner
            .withBean(VectorDatabaseService.class, () -> customService)
            .withPropertyValues("ai.vector-db.type=memory")
            .run(context -> {
                assertThat(context).doesNotHaveBean(InMemoryVectorDatabaseService.class);
                assertThat(context).hasSingleBean(VectorDatabaseService.class);
                assertThat(context.getBean(VectorDatabaseService.class)).isSameAs(customService);
            });
    }
}
