package ai.fabric.intent.action.handlers;

import ai.fabric.rag.VectorDatabaseService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class VectorActionHandlersConditionalRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfig.class);

    @Test
    void vectorManagementActionsAreDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ClearVectorIndexActionHandler.class);
            assertThat(context).doesNotHaveBean(RemoveVectorActionHandler.class);
        });
    }

    @Test
    void vectorManagementActionsCanBeEnabledExplicitly() {
        contextRunner
            .withPropertyValues(
                "ai.vector-db.type=lucene",
                "ai.actions.builtin.vector-management.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(ClearVectorIndexActionHandler.class);
                assertThat(context).hasSingleBean(RemoveVectorActionHandler.class);
            });
    }

    @Configuration
    @Import({
        ClearVectorIndexActionHandler.class,
        RemoveVectorActionHandler.class
    })
    static class TestConfig {

        @Bean
        VectorDatabaseService vectorDatabaseService() {
            return mock(VectorDatabaseService.class);
        }
    }
}
