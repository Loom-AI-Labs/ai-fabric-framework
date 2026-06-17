package ai.fabric.rag.config;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.service.RAGService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.vector.VectorDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RAGAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RAGAutoConfiguration.class))
        .withBean(AIProviderConfig.class, AIProviderConfig::new)
        .withBean(AIEmbeddingService.class, () -> mock(AIEmbeddingService.class))
        .withBean(VectorDatabaseService.class, () -> mock(VectorDatabaseService.class))
        .withBean(VectorDatabase.class, () -> mock(VectorDatabase.class))
        .withBean(AISearchService.class, () -> mock(AISearchService.class));

    @Test
    void createsDefaultRagProviderWhenEnabledAndDependenciesExist() {
        contextRunner
            .withPropertyValues("ai.infrastructure.rag.advanced.enabled=false")
            .run(context -> {
                assertThat(context).hasSingleBean(RAGService.class);
                assertThat(context).hasSingleBean(RAGProvider.class);
            });
    }

    @Test
    void doesNotCreateRagProviderWhenModuleDisabled() {
        contextRunner
            .withPropertyValues("ai.infrastructure.rag.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(RAGService.class);
                assertThat(context).doesNotHaveBean(RAGProvider.class);
            });
    }

    @Test
    void backsOffWhenApplicationProvidesCustomRagProvider() {
        RAGProvider customProvider = mock(RAGProvider.class);

        contextRunner
            .withBean(RAGProvider.class, () -> customProvider)
            .withPropertyValues("ai.infrastructure.rag.advanced.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(RAGService.class);
                assertThat(context).hasSingleBean(RAGProvider.class);
                assertThat(context.getBean(RAGProvider.class)).isSameAs(customProvider);
            });
    }
}
