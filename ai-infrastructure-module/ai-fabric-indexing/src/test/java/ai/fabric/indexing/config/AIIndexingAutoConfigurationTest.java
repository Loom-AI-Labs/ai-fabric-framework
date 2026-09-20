package ai.fabric.indexing.config;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.api.IndexingWorkQuery;
import ai.fabric.indexing.document.DocumentChunkIdentity;
import ai.fabric.indexing.document.DocumentIndexingQueueAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.indexing.queue.IndexingQueueService;
import ai.fabric.indexing.query.DefaultIndexingWorkQuery;
import ai.fabric.repository.IndexingQueueRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AIIndexingAutoConfigurationTest {

    @Test
    void createsThePublicWorkQueryFromTheInternalRepository() {
        IndexingWorkQuery query = new AIIndexingAutoConfiguration()
            .indexingWorkQuery(mock(IndexingQueueRepository.class));

        assertThat(query).isInstanceOf(DefaultIndexingWorkQuery.class);
    }

    @Test
    void providerOnlyApplicationDoesNotActivateDocumentIndexing() {
        contextRunner()
            .withPropertyValues(
                "ai.service.features.enable-search=false",
                "ai.service.features.enable-embeddings=false"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SpringAiDocumentIndexingAdapter.class);
                assertThat(context).doesNotHaveBean(SpringAiDocumentReaderFactory.class);
            });
    }

    @Test
    void explicitlyDisabledIndexingDoesNotActivateDocumentIndexing() {
        contextRunner()
            .withPropertyValues(
                "ai.indexing.enabled=false",
                "ai.vector-db.type=memory"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(SpringAiDocumentIndexingAdapter.class);
                assertThat(context).doesNotHaveBean(SpringAiDocumentReaderFactory.class);
            });
    }

    @Test
    void documentConfigurationCreatesPlanningAndQueueBoundaries() {
        documentContextRunner()
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(DocumentChunkIdentity.class);
                assertThat(context).hasSingleBean(
                    SpringAiDocumentIndexingAdapter.class
                );
                assertThat(context).hasSingleBean(
                    DocumentIndexingQueueAdapter.class
                );
                assertThat(context).hasSingleBean(
                    SpringAiDocumentReaderFactory.class
                );
            });
    }

    @Test
    void documentConfigurationCanBeDisabledIndependently() {
        documentContextRunner()
            .withPropertyValues("ai.indexing.documents.enabled=false")
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(
                    SpringAiDocumentIndexingAdapter.class
                );
                assertThat(context).doesNotHaveBean(
                    DocumentIndexingQueueAdapter.class
                );
            });
    }

    @Test
    void documentConfigurationBacksOffWithoutSpringAiDocuments() {
        documentContextRunner()
            .withClassLoader(new FilteredClassLoader(
                "org.springframework.ai.document"
            ))
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(
                    SpringAiDocumentIndexingAdapter.class
                );
            });
    }

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AIIndexingAutoConfiguration.class))
            .withBean(
                AIEntityConfigurationLoader.class,
                () -> mock(AIEntityConfigurationLoader.class)
            )
            .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    private ApplicationContextRunner documentContextRunner() {
        return new ApplicationContextRunner()
            .withUserConfiguration(
                AIIndexingAutoConfiguration.SpringAiDocumentIndexingConfiguration.class
            )
            .withPropertyValues("ai.vector-db.type=memory")
            .withBean(
                AIEntityConfigurationLoader.class,
                () -> mock(AIEntityConfigurationLoader.class)
            )
            .withBean(
                IndexingQueueService.class,
                () -> mock(IndexingQueueService.class)
            );
    }
}
