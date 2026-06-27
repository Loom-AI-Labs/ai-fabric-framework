package ai.fabric.intent.retrieval.connector.config;

import ai.fabric.http.AIHttpClientFactory;
import ai.fabric.intent.retrieval.connector.AIRetrievalConnectorProperties;
import ai.fabric.intent.retrieval.connector.RetrievalConnectorRAGProvider;
import ai.fabric.spi.RAGProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class AIRetrievalConnectorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(AIRetrievalConnectorAutoConfiguration.class));

    @Test
    void createsRetrievalConnectorProviderWhenEnabled() {
        contextRunner
            .withBean(AIHttpClientFactory.class, () -> mock(AIHttpClientFactory.class))
            .withPropertyValues(
                "ai.retrieval.connector.enabled=true",
                "ai.retrieval.connector.base-url=https://connector.example"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(RetrievalConnectorRAGProvider.class);
                assertThat(context).hasSingleBean(RAGProvider.class);
                assertThat(context).hasSingleBean(AIRetrievalConnectorProperties.class);
                assertThat(context.getBean(RetrievalConnectorRAGProvider.class).getStatistics())
                    .containsEntry("enabled", true)
                    .containsEntry("baseUrlConfigured", true);
            });
    }

    @Test
    void createsProviderWithoutRequiringApplicationClockBean() {
        contextRunner
            .withBean(AIHttpClientFactory.class, () -> mock(AIHttpClientFactory.class))
            .withPropertyValues(
                "ai.retrieval.connector.enabled=true",
                "ai.retrieval.connector.base-url=https://connector.example"
            )
            .run(context -> {
                assertThat(context).doesNotHaveBean(Clock.class);
                assertThat(context).hasSingleBean(RetrievalConnectorRAGProvider.class);
            });
    }

    @Test
    void backsOffWhenApplicationProvidesRagProvider() {
        RAGProvider customProvider = mock(RAGProvider.class);

        contextRunner
            .withBean(RAGProvider.class, () -> customProvider)
            .withPropertyValues(
                "ai.retrieval.connector.enabled=true",
                "ai.retrieval.connector.base-url=https://connector.example"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(RAGProvider.class);
                assertThat(context).doesNotHaveBean(RetrievalConnectorRAGProvider.class);
                assertThat(context.getBean(RAGProvider.class)).isSameAs(customProvider);
            });
    }

    @Test
    void remainsDisabledByDefault() {
        contextRunner
            .withBean(AIHttpClientFactory.class, () -> mock(AIHttpClientFactory.class))
            .withPropertyValues("ai.retrieval.connector.base-url=https://connector.example")
            .run(context -> assertThat(context).doesNotHaveBean(RetrievalConnectorRAGProvider.class));
    }

    @Test
    void failsFastWhenEnabledWithoutBaseUrl() {
        contextRunner
            .withBean(AIHttpClientFactory.class, () -> mock(AIHttpClientFactory.class))
            .withPropertyValues("ai.retrieval.connector.enabled=true")
            .run(context -> assertThat(context.getStartupFailure())
                .hasRootCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ai.retrieval.connector.baseUrl is required"));
    }
}
