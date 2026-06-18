package com.ai.fabric.examples.smoke;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Registers the offline deterministic provider beans only when the {@code smoke} profile is active.
 *
 * <p>Combined with the bundled {@code application-smoke.yml} (which points the LLM/embedding/vector
 * selectors at these local providers), depending on this module is enough for an example app to boot with
 * {@code --spring.profiles.active=smoke} and no external credentials or services.</p>
 */
@AutoConfiguration
@Profile("smoke")
public class SmokeProfileAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(SmokeAiProvider.class)
    public SmokeAiProvider smokeAiProvider() {
        return new SmokeAiProvider();
    }

    @Bean
    @ConditionalOnMissingBean(SmokeEmbeddingProvider.class)
    public SmokeEmbeddingProvider smokeEmbeddingProvider() {
        return new SmokeEmbeddingProvider();
    }
}
