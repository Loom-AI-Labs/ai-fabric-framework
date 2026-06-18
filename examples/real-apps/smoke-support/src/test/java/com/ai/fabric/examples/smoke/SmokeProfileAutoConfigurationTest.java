package com.ai.fabric.examples.smoke;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class SmokeProfileAutoConfigurationTest {

    @Test
    void registersProvidersOnlyForSmokeProfile() {
        new ApplicationContextRunner()
            .withUserConfiguration(SmokeProfileAutoConfiguration.class)
            .withPropertyValues("spring.profiles.active=smoke")
            .run(context -> {
                assertThat(context).hasSingleBean(SmokeAiProvider.class);
                assertThat(context).hasSingleBean(SmokeEmbeddingProvider.class);
            });
    }

    @Test
    void leavesContextUnchangedWhenSmokeProfileIsInactive() {
        new ApplicationContextRunner()
            .withUserConfiguration(SmokeProfileAutoConfiguration.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean(SmokeAiProvider.class);
                assertThat(context).doesNotHaveBean(SmokeEmbeddingProvider.class);
            });
    }
}
