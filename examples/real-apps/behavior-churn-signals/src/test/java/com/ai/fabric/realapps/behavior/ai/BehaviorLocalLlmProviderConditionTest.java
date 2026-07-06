package com.ai.fabric.realapps.behavior.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorLocalLlmProviderConditionTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(LocalProviderConfiguration.class);

    @Test
    void registersLocalProviderWhenBehaviorLocalIsSelected() {
        contextRunner
            .withPropertyValues("ai.providers.llm-provider=behavior-local")
            .run(context -> assertThat(context).hasSingleBean(BehaviorLocalLlmProvider.class));
    }

    @Test
    void registersLocalProviderByDefaultForNoKeyLocalRuns() {
        contextRunner
            .run(context -> assertThat(context).hasSingleBean(BehaviorLocalLlmProvider.class));
    }

    @Test
    void doesNotRegisterLocalProviderWhenOpenAiIsSelected() {
        contextRunner
            .withPropertyValues("ai.providers.llm-provider=openai")
            .run(context -> assertThat(context).doesNotHaveBean(BehaviorLocalLlmProvider.class));
    }

    @Configuration(proxyBeanMethods = false)
    @Import(BehaviorLocalLlmProvider.class)
    static class LocalProviderConfiguration {
    }
}
