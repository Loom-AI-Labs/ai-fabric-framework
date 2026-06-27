package ai.fabric.rag.config;

import ai.fabric.rag.evaluation.springai.SpringAiRagEvaluationService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RAGAutoConfigurationEvaluationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RAGAutoConfiguration.class))
        .withPropertyValues("ai.infrastructure.rag.advanced.enabled=false")
        .withBean(ChatClient.Builder.class, () -> mock(ChatClient.Builder.class));

    @Test
    void doesNotRegisterSpringAiEvaluatorsByDefault() {
        contextRunner.run(context ->
            assertThat(context).doesNotHaveBean(SpringAiRagEvaluationService.class)
        );
    }

    @Test
    void registersSpringAiEvaluatorsWhenExplicitlyEnabled() {
        contextRunner
            .withPropertyValues("ai.infrastructure.rag.evaluation.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(SpringAiRagEvaluationService.class);
                assertThat(context).hasBean("springAiRagRelevancyEvaluator");
                assertThat(context).hasBean("springAiRagFactCheckingEvaluator");
            });
    }
}
