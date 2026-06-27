package ai.fabric.rag.config;

import ai.fabric.config.AIProviderConfig;
import ai.fabric.core.AIEmbeddingService;
import ai.fabric.core.AISearchService;
import ai.fabric.dto.AIEmbeddingResponse;
import ai.fabric.dto.AISearchRequest;
import ai.fabric.dto.AISearchResponse;
import ai.fabric.dto.RAGRequest;
import ai.fabric.rag.VectorDatabaseService;
import ai.fabric.rag.service.RAGService;
import ai.fabric.spi.RAGProvider;
import ai.fabric.vector.VectorDatabase;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void passesBoundRagPropertiesToDefaultRagService() {
        AIEmbeddingService embeddingService = mock(AIEmbeddingService.class);
        VectorDatabaseService vectorDatabaseService = mock(VectorDatabaseService.class);
        VectorDatabase vectorDatabase = mock(VectorDatabase.class);
        AISearchService searchService = mock(AISearchService.class);

        when(embeddingService.executeEmbedding(any())).thenReturn(new AIEmbeddingService.EmbeddingExecution(
            AIEmbeddingResponse.builder()
                .embedding(List.of(0.1, 0.2, 0.3))
                .build(),
            false,
            "test",
            "test-embedding",
            1L,
            1L
        ));
        when(searchService.search(any(), any())).thenReturn(AISearchResponse.builder()
            .results(List.of())
            .totalResults(0)
            .build());

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RAGAutoConfiguration.class))
            .withBean(AIProviderConfig.class, AIProviderConfig::new)
            .withBean(AIEmbeddingService.class, () -> embeddingService)
            .withBean(VectorDatabaseService.class, () -> vectorDatabaseService)
            .withBean(VectorDatabase.class, () -> vectorDatabase)
            .withBean(AISearchService.class, () -> searchService)
            .withPropertyValues(
                "ai.infrastructure.rag.advanced.enabled=false",
                "ai.infrastructure.rag.default-limit=4",
                "ai.infrastructure.rag.default-threshold=0.33"
            )
            .run(context -> {
                RAGService service = context.getBean(RAGService.class);

                service.performRag(RAGRequest.builder()
                    .query("configured defaults")
                    .entityType("faq")
                    .build());

                ArgumentCaptor<AISearchRequest> searchRequest = ArgumentCaptor.forClass(AISearchRequest.class);
                verify(searchService).search(any(), searchRequest.capture());
                assertThat(searchRequest.getValue())
                    .extracting(AISearchRequest::getLimit, AISearchRequest::getThreshold)
                    .containsExactly(4, 0.33d);
            });
    }

    @Test
    void createsBoundedAdvancedRagSearchExecutorFromProperties() {
        contextRunner
            .withPropertyValues(
                "ai.infrastructure.rag.advanced.max-parallel-searches=2",
                "ai.infrastructure.rag.advanced.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasBean("advancedRagSearchExecutor");
                ExecutorService executor = context.getBean("advancedRagSearchExecutor", ExecutorService.class);
                assertThat(executor).isInstanceOf(ThreadPoolExecutor.class);
                ThreadPoolExecutor threadPoolExecutor = (ThreadPoolExecutor) executor;
                assertThat(threadPoolExecutor.getCorePoolSize()).isEqualTo(2);
                assertThat(threadPoolExecutor.getMaximumPoolSize()).isEqualTo(2);
            });
    }
}
