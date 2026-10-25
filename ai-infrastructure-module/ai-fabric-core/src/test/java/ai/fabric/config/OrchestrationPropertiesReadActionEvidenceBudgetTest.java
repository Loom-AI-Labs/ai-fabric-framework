package ai.fabric.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class OrchestrationPropertiesReadActionEvidenceBudgetTest {

    private static final String READ_ACTION_PREFIX =
        "ai.orchestration.modes.resolver.read-action-resolution.";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(PropertyConfiguration.class);

    @Test
    void defaultsEvidenceItemBudgetToFiveWhenOmitted() {
        assertThat(new OrchestrationProperties.ReadActionResolutionModeOverrides().getMaxActionEvidenceItems())
            .isEqualTo(5);

        contextRunner.withPropertyValues(READ_ACTION_PREFIX + "enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OrchestrationProperties.class).getModes().get("resolver")
                .getReadActionResolution().getMaxActionEvidenceItems()).isEqualTo(5);
        });
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 17, 100})
    void bindsEvidenceItemBudgetWithinBounds(int budget) {
        contextRunner.withPropertyValues(READ_ACTION_PREFIX + "max-action-evidence-items=" + budget)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context.getBean(OrchestrationProperties.class).getModes().get("resolver")
                    .getReadActionResolution().getMaxActionEvidenceItems()).isEqualTo(budget);
            });
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 101, Integer.MAX_VALUE})
    void rejectsOutOfRangeEvidenceItemBudgetEvenForUnusedMode(int budget) {
        contextRunner.withPropertyValues(READ_ACTION_PREFIX + "max-action-evidence-items=" + budget)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining("maxActionEvidenceItems must be between 1 and 100");
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "1.5", "invalid"})
    void rejectsExplicitEmptyOrNonIntegerEvidenceItemBudget(String budget) {
        contextRunner.withPropertyValues(READ_ACTION_PREFIX + "max-action-evidence-items=" + budget)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("max-action-evidence-items");
            });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OrchestrationProperties.class)
    static class PropertyConfiguration {
    }
}
