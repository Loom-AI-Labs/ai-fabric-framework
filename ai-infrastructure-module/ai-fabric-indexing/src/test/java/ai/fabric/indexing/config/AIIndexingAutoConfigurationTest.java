package ai.fabric.indexing.config;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AIIndexingAutoConfigurationTest {

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

    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AIIndexingAutoConfiguration.class))
            .withBean(
                AIEntityConfigurationLoader.class,
                () -> mock(AIEntityConfigurationLoader.class)
            )
            .withBean(ObjectMapper.class, ObjectMapper::new);
    }
}
