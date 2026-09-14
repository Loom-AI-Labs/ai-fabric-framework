package ai.fabric.execution.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.chain.SpecialistChainGateway;
import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.chain.state.InMemorySpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.JdbcSpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class AIExecutionSpecialistChainAutoConfigurationTest {

    private static final String ENCRYPTION_SECRET =
        "chain-encryption-secret-at-least-32-characters";
    private static final String FINGERPRINT_SECRET =
        "chain-fingerprint-secret-at-least-32-characters";

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AIExecutionSpecialistChainJdbcAutoConfiguration.class,
                AIExecutionAutoConfiguration.class,
                AIExecutionSpecialistChainAutoConfiguration.class
            ))
            .withUserConfiguration(
                AIExecutionAutoConfigurationTest
                    .InfrastructureConfiguration.class
            );

    @Test
    void chainRuntimeIsAbsentByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(SpecialistChainRegistry.class);
            assertThat(context).doesNotHaveBean(SpecialistChainGateway.class);
            assertThat(context)
                .doesNotHaveBean(SpecialistChainExecutionRepository.class);
        });
    }

    @Test
    void explicitEphemeralModeConfiguresBoundedRuntime() {
        contextRunner
            .withPropertyValues(
                "ai.execution.specialist-chains.enabled=true",
                "ai.execution.specialist-chains.allow-ephemeral=true"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SpecialistChainRegistry.class);
                assertThat(context).hasSingleBean(SpecialistChainGateway.class);
                assertThat(context).hasBean(
                    TaskManagementConfigUtils
                        .SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME
                );
                assertThat(context)
                    .getBean(SpecialistChainExecutionRepository.class)
                    .isInstanceOf(
                        InMemorySpecialistChainExecutionRepository.class
                    );
            });
    }

    @Test
    void unacknowledgedEphemeralModeFailsStartup() {
        contextRunner
            .withPropertyValues(
                "ai.execution.specialist-chains.enabled=true"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("allow-ephemeral=true");
            });
    }

    @Test
    void durableModeConfiguresJdbcRepository() {
        contextRunner
            .withBean(DataSource.class, this::dataSource)
            .withPropertyValues(durableProperties())
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(SpecialistChainGateway.class);
                assertThat(context)
                    .getBean(SpecialistChainExecutionRepository.class)
                    .isInstanceOf(
                        JdbcSpecialistChainExecutionRepository.class
                    );
            });
    }

    @Test
    void durableModeFailsWithoutDataSource() {
        contextRunner
            .withPropertyValues(durableProperties())
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(
                        "Durable specialist chains require a DataSource"
                    );
            });
    }

    @Test
    void durableModeFailsWithoutStrongDistinctSecrets() {
        contextRunner
            .withBean(DataSource.class, this::dataSource)
            .withPropertyValues(
                "ai.execution.specialist-chains.enabled=true",
                "ai.execution.specialist-chains.durable-enabled=true",
                "ai.execution.specialist-chains.initialize-schema=true",
                "ai.execution.specialist-chains.encryption-secret=short",
                "ai.execution.specialist-chains.fingerprint-secret=short"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining("two distinct secrets");
            });
    }

    private String[] durableProperties() {
        return new String[] {
            "ai.execution.specialist-chains.enabled=true",
            "ai.execution.specialist-chains.durable-enabled=true",
            "ai.execution.specialist-chains.initialize-schema=true",
            "ai.execution.specialist-chains.encryption-secret="
                + ENCRYPTION_SECRET,
            "ai.execution.specialist-chains.fingerprint-secret="
                + FINGERPRINT_SECRET
        };
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:chain-auto-configuration-"
                + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }
}
