package ai.fabric.execution.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.fabric.execution.chain.SpecialistChainDefinition;
import ai.fabric.execution.chain.SpecialistChainGateway;
import ai.fabric.execution.chain.SpecialistChainRegistry;
import ai.fabric.execution.chain.manifest.SpecialistChainManifestRuntimeStatus;
import ai.fabric.execution.chain.state.InMemorySpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.JdbcSpecialistChainExecutionRepository;
import ai.fabric.execution.chain.state.SpecialistChainExecutionRepository;
import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

class AIExecutionSpecialistChainAutoConfigurationTest {

    @TempDir
    Path tempDirectory;

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
            assertThat(context)
                .hasSingleBean(SpecialistChainManifestRuntimeStatus.class);
            assertThat(context.getBean(
                SpecialistChainManifestRuntimeStatus.class
            )).satisfies(status -> {
                assertThat(status.ready()).isTrue();
                assertThat(status.totalRegisteredCount()).isZero();
            });
        });
    }

    @Test
    void disabledRuntimeReportsDiscoveredJavaDefinitionsAsInactive() {
        contextRunner
            .withBean(
                SpecialistChainDefinition.class,
                () -> org.mockito.Mockito.mock(
                    SpecialistChainDefinition.class
                )
            )
            .run(context -> {
                SpecialistChainManifestRuntimeStatus status = context.getBean(
                    SpecialistChainManifestRuntimeStatus.class
                );

                assertThat(status.javaDefinedCount()).isEqualTo(1);
                assertThat(status.totalRegisteredCount()).isZero();
                assertThat(context)
                    .doesNotHaveBean(SpecialistChainRegistry.class);
            });
    }

    @Test
    void discoveredManifestChainFailsWhenChainExecutionIsDisabled()
        throws Exception {
        Path manifest = tempDirectory.resolve("disabled-chain.yml");
        Files.writeString(manifest, chainManifest());

        contextRunner
            .withPropertyValues(
                "ai.execution.manifests.enabled=true",
                "ai.execution.manifests.locations[0]=file:"
                    + manifest.toAbsolutePath()
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining(
                        "A SpecialistChain resource requires"
                    );
            });
    }

    @Test
    void diagnosticsModeRetainsInactiveManifestWithoutRequiringJdbc()
        throws Exception {
        Path manifest = tempDirectory.resolve("inactive-chain.yml");
        Files.writeString(manifest, chainManifest());

        contextRunner
            .withPropertyValues(
                "ai.execution.manifests.enabled=true",
                "ai.execution.manifests.fail-fast=false",
                "ai.execution.manifests.locations[0]=file:"
                    + manifest.toAbsolutePath()
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                    .doesNotHaveBean(SpecialistChainRegistry.class);
                assertThat(context)
                    .doesNotHaveBean(SpecialistChainExecutionRepository.class);
                SpecialistChainManifestRuntimeStatus status = context.getBean(
                    SpecialistChainManifestRuntimeStatus.class
                );
                assertThat(status.ready()).isFalse();
                assertThat(status.discoveredManifestCount()).isEqualTo(1);
                assertThat(status.inactiveManifestCount()).isEqualTo(1);
                assertThat(status.manifestDefinedCount()).isZero();
                assertThat(status.diagnostics()).singleElement().satisfies(
                    diagnostic -> assertThat(diagnostic.reason())
                        .isEqualTo("CHAIN_MANIFEST_FEATURE_DISABLED")
                );
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

    private String chainManifest() {
        return """
            apiVersion: ai.fabric/v1
            kind: SpecialistChain
            metadata:
              name: disabled-chain
              version: "1"
              displayName: Disabled chain
              description: Proves an inactive chain is never ignored.
              labels: {}
            spec:
              input:
                schemaRef: disabled-input@1
                managerMessagePointer: /question
                managerContext: []
              manager:
                specialistRef: disabled-manager@1
              targets:
                - specialistRef: disabled-reader@1
                  description: Reads approved evidence.
                  input:
                    type: JSON_POINTER_MAP
                    fields:
                      - source: CHAIN_INPUT
                        sourcePointer: /question
                        targetField: question
                  result:
                    type: BOUNDED_FACT_PROJECTION
                    summaryPointer: /summary
                    facts: []
                    evidenceReferences: NONE
                  transitions:
                    delegationAllowed: true
                    parallelEligible: false
                    handoffAllowed: false
              limits:
                maxDuration: PT30S
                maxManagerDecisions: 2
                maxWorkerInvocations: 1
                maxParallelWorkers: 1
                maxInvocationsPerTarget: 1
                maxProjectedResultCharacters: 2000
              conversationPolicy: DISABLED
            """;
    }
}
