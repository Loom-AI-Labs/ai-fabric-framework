package ai.fabric.intent.action.connector.registry.liquibase;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AIActionDbRegistryLiquibaseEnvironmentPostProcessorTest {

    @Test
    void postProcessEnvironment_disablesSpringLiquibaseByDefault_whenNoExistingConfigAndDbRegistryDisabled() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "ai.actions.db.enabled", "false"
        )));

        AIActionDbRegistryLiquibaseEnvironmentPostProcessor pp = new AIActionDbRegistryLiquibaseEnvironmentPostProcessor();
        pp.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.liquibase.enabled")).isEqualTo("false");
        assertThat(env.getProperty("spring.liquibase.change-log")).isNull();
    }

    @Test
    void postProcessEnvironment_setsSpringLiquibaseChangeLog_whenDbRegistryEnabled() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "ai.actions.db.enabled", "true"
        )));

        AIActionDbRegistryLiquibaseEnvironmentPostProcessor pp = new AIActionDbRegistryLiquibaseEnvironmentPostProcessor();
        pp.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.liquibase.enabled")).isEqualTo("true");
        assertThat(env.getProperty("spring.liquibase.change-log"))
            .isEqualTo("classpath:db/changelog/ai-actions-registry-changelog.yaml");
    }

    @Test
    void postProcessEnvironment_doesNotOverride_whenSpringLiquibaseChangeLogConfigured() {
        ConfigurableEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", Map.of(
            "ai.actions.db.enabled", "true",
            "spring.liquibase.change-log", "classpath:/db/changelog/db.changelog-master.yaml"
        )));

        AIActionDbRegistryLiquibaseEnvironmentPostProcessor pp = new AIActionDbRegistryLiquibaseEnvironmentPostProcessor();
        pp.postProcessEnvironment(env, new SpringApplication());

        assertThat(env.getProperty("spring.liquibase.change-log")).isEqualTo("classpath:/db/changelog/db.changelog-master.yaml");
    }
}

