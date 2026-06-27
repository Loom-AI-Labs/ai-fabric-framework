package ai.fabric.indexing.config;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.indexing.document.springai.SpringAiDocumentIndexingAdapter;
import ai.fabric.indexing.document.springai.SpringAiDocumentReaderFactory;
import ai.fabric.repository.IndexingQueueRepository;
import ai.fabric.service.AICapabilityService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AIIndexingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AIIndexingAutoConfiguration.class))
        .withPropertyValues("ai.vector-db.type=memory")
        .withBean(IndexingQueueRepository.class, () -> mock(IndexingQueueRepository.class))
        .withBean(AIEntityConfigurationLoader.class, () -> mock(AIEntityConfigurationLoader.class))
        .withBean(AICapabilityService.class, () -> mock(AICapabilityService.class))
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(Clock.class, Clock::systemUTC);

    @Test
    void registersSpringAiDocumentHelpersWhenSpringAiCommonsIsPresent() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SpringAiDocumentIndexingAdapter.class);
            assertThat(context).hasSingleBean(SpringAiDocumentReaderFactory.class);
        });
    }

    @Test
    void doesNotRegisterSpringAiDocumentIndexingAdapterWithoutQueueService() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AIIndexingAutoConfiguration.class))
            .withPropertyValues(
                "ai.service.features.enable-search=false",
                "ai.service.features.enable-embeddings=false"
            )
            .withBean(AIEntityConfigurationLoader.class, () -> mock(AIEntityConfigurationLoader.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .run(context -> {
                assertThat(context).doesNotHaveBean(SpringAiDocumentIndexingAdapter.class);
                assertThat(context).doesNotHaveBean(SpringAiDocumentReaderFactory.class);
            });
    }

    @Test
    void doesNotRegisterSpringAiDocumentIndexingAdapterWhenIndexingDisabled() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AIIndexingAutoConfiguration.class))
            .withPropertyValues(
                "ai.indexing.enabled=false",
                "ai.vector-db.type=memory"
            )
            .withBean(AIEntityConfigurationLoader.class, () -> mock(AIEntityConfigurationLoader.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .run(context -> {
                assertThat(context).doesNotHaveBean(SpringAiDocumentIndexingAdapter.class);
                assertThat(context).doesNotHaveBean(SpringAiDocumentReaderFactory.class);
            });
    }
}
