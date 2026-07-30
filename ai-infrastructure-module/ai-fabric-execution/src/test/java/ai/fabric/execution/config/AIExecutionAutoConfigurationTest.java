package ai.fabric.execution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.execution.gateway.AIExecutionConversationSnapshotProvider;
import ai.fabric.execution.gateway.AIExecutionConversationSnapshotRegistry;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIInteractiveExecutionGateway;
import ai.fabric.execution.gateway.DurableAIExecutionGateway;
import ai.fabric.execution.gateway.ExecutionCapabilityInventory;
import ai.fabric.execution.gateway.SharedInteractiveTurnCoordinator;
import ai.fabric.execution.delegation.SpecialistDelegationGateway;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.manager.ConversationManagerGateway;
import ai.fabric.execution.manager.ConversationManagerRegistry;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.plan.PlanComponentRegistry;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistAuthoringCatalogProvider;
import ai.fabric.execution.specialist.manifest.SpecialistManifestMetrics;
import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AIExecutionAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner =
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(AIExecutionAutoConfiguration.class)
            )
            .withUserConfiguration(InfrastructureConfiguration.class);

    @Test
    void configuresGatewayRegistryInventoryAndBoundedExecutor() {
        contextRunner
            .withPropertyValues(
                "ai.execution.capabilities.registered-vector-spaces=account-policy",
                "ai.execution.capabilities.allowed-actions=inspect_account",
                "ai.execution.async.core-pool-size=1",
                "ai.execution.async.max-pool-size=2",
                "ai.execution.async.queue-capacity=3"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(AIExecutionGateway.class);
                assertThat(context)
                    .hasSingleBean(SpecialistDelegationGateway.class);
                assertThat(context)
                    .hasSingleBean(SpecialistHandoffGateway.class);
                assertThat(context)
                    .hasSingleBean(ConversationManagerRegistry.class);
                assertThat(context.getBean(
                    ConversationManagerRegistry.class
                ).list()).isEmpty();
                assertThat(context)
                    .hasSingleBean(AIExecutionCoordinator.class);
                assertThat(context)
                    .hasSingleBean(ExecutionPlanRegistry.class);
                assertThat(context)
                    .hasSingleBean(PlanComponentRegistry.class);
                assertThat(context.getBean(
                    ExecutionPlanRegistry.class
                ).list()).isEmpty();
                assertThat(context).hasSingleBean(SpecialistRegistry.class);
                assertThat(context)
                    .hasSingleBean(SpecialistAuthoringCatalogProvider.class);
                assertThat(context).hasSingleBean(ExecutionCapabilityInventory.class);
                assertThat(context)
                    .getBean(ExecutionCapabilityInventory.class)
                    .satisfies(inventory -> {
                        assertThat(inventory.registeredVectorSpaces())
                            .containsExactly("account-policy");
                        assertThat(inventory.deploymentAllowedActions())
                            .containsExactly("inspect_account");
                    });
                assertThat(context.getBean(
                    SpecialistAuthoringCatalogProvider.class
                ).catalog())
                    .satisfies(catalog -> {
                        assertThat(catalog.modes()).contains("resolver");
                        assertThat(catalog.vectorSpaces())
                            .containsExactly("account-policy");
                        assertThat(catalog.actions())
                            .extracting(action -> action.name())
                            .containsExactly("inspect_account");
                    });
                assertThat(context)
                    .getBean("aiFabricExecutionTaskExecutor")
                    .isInstanceOfSatisfying(
                        ThreadPoolTaskExecutor.class,
                        executor -> {
                            assertThat(executor.getCorePoolSize()).isEqualTo(1);
                            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
                            assertThat(executor.getQueueCapacity()).isEqualTo(3);
                        }
                    );
            });
    }

    @Test
    void canDisablePlanCoordinationWithoutDisablingSpecialistExecution() {
        contextRunner
            .withPropertyValues("ai.execution.plans.enabled=false")
            .run(context -> {
                assertThat(context).hasSingleBean(AIExecutionGateway.class);
                assertThat(context)
                    .hasSingleBean(ExecutionPlanRegistry.class);
                assertThat(context)
                    .doesNotHaveBean(AIExecutionCoordinator.class);
            });
    }

    @Test
    void canDisableConversationManagersWithoutDisablingSpecialists() {
        contextRunner
            .withPropertyValues(
                "ai.execution.conversation-managers.enabled=false"
            )
            .run(context -> {
                assertThat(context).hasSingleBean(AIExecutionGateway.class);
                assertThat(context)
                    .doesNotHaveBean(ConversationManagerRegistry.class);
            });
    }

    @Test
    void isAbsentWhenExplicitlyDisabled() {
        contextRunner
            .withPropertyValues("ai.execution.enabled=false")
            .run(context -> {
                assertThat(context).doesNotHaveBean(AIExecutionGateway.class);
                assertThat(context).doesNotHaveBean(SpecialistRegistry.class);
                assertThat(context)
                    .doesNotHaveBean("aiFabricExecutionTaskExecutor");
            });
    }

    @Test
    void publishesManifestMetricsWhenMicrometerIsAvailable() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        contextRunner
            .withBean(SimpleMeterRegistry.class, () -> meterRegistry)
            .run(context -> {
                context.getBean(SpecialistManifestMetrics.class)
                    .recordLoad("success", "none");

                assertThat(meterRegistry.counter(
                    "ai.fabric.specialist.manifest.load",
                    "result",
                    "success",
                    "reason",
                    "none"
                ).count()).isEqualTo(1);
            });
    }

    @Test
    void integratesValidatedConversationRecordingWhenChatSessionIsPresent() {
        new ApplicationContextRunner()
            .withConfiguration(
                AutoConfigurations.of(AIExecutionChatSessionAutoConfiguration.class)
            )
            .withBean(ChatSessionService.class, () -> mock(ChatSessionService.class))
            .run(context ->
                assertThat(context)
                    .hasSingleBean(AIExecutionConversationRecorder.class)
            );
    }

    @Test
    void configuresInteractiveDialogueBoundaryWithFullRuntime() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AIExecutionAutoConfiguration.class,
                AIExecutionChatSessionAutoConfiguration.class
            ))
            .withUserConfiguration(InfrastructureConfiguration.class)
            .withBean(
                ChatSessionService.class,
                () -> mock(ChatSessionService.class)
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context)
                    .hasSingleBean(AIExecutionConversationRecorder.class);
                assertThat(context).hasSingleBean(
                    AIExecutionConversationSnapshotProvider.class
                );
                assertThat(context).hasSingleBean(
                    AIExecutionConversationSnapshotRegistry.class
                );
                assertThat(context)
                    .hasSingleBean(AIInteractiveExecutionGateway.class);
                assertThat(context).hasSingleBean(
                    SharedInteractiveTurnCoordinator.class
                );
                assertThat(context)
                    .hasSingleBean(ConversationManagerGateway.class);
            });
    }

    @Test
    void selectsDurableGatewayWhenJdbcStateIsFullyConfigured() {
        JdbcDataSource dataSource = dataSource();

        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AIExecutionJdbcStateAutoConfiguration.class,
                AIExecutionAutoConfiguration.class
            ))
            .withUserConfiguration(InfrastructureConfiguration.class)
            .withBean(javax.sql.DataSource.class, () -> dataSource)
            .withPropertyValues(
                "ai.execution.async.repository=JDBC",
                "ai.execution.async.encryption-secret="
                    + "execution-encryption-secret-for-tests-0001",
                "ai.execution.async.fingerprint-secret="
                    + "execution-fingerprint-secret-for-tests-0002"
            )
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(
                    DurableExecutionRepository.class
                );
                assertThat(context.getBean(AIExecutionGateway.class))
                    .isInstanceOf(DurableAIExecutionGateway.class);
            });
    }

    @Test
    void jdbcSelectionFailsClosedWithoutDataSource() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AIExecutionJdbcStateAutoConfiguration.class,
                AIExecutionAutoConfiguration.class
            ))
            .withUserConfiguration(InfrastructureConfiguration.class)
            .withPropertyValues(
                "ai.execution.async.repository=JDBC",
                "ai.execution.async.encryption-secret="
                    + "execution-encryption-secret-for-tests-0001",
                "ai.execution.async.fingerprint-secret="
                    + "execution-fingerprint-secret-for-tests-0002"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasStackTraceContaining(
                        "repository=JDBC requires a DataSource"
                    );
            });
    }

    @Test
    void jdbcSelectionFailsClosedWithoutStrongDistinctSecrets() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                AIExecutionJdbcStateAutoConfiguration.class,
                AIExecutionAutoConfiguration.class
            ))
            .withUserConfiguration(InfrastructureConfiguration.class)
            .withBean(javax.sql.DataSource.class, this::dataSource)
            .withPropertyValues(
                "ai.execution.async.repository=JDBC",
                "ai.execution.async.encryption-secret=short",
                "ai.execution.async.fingerprint-secret=short"
            )
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasStackTraceContaining(
                        "encryptionSecret must contain at least 32 characters"
                    );
            });
    }

    private JdbcDataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(
            "jdbc:h2:mem:execution-auto-config-"
                + java.util.UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1"
        );
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    @Configuration(proxyBeanMethods = false)
    static class InfrastructureConfiguration {

        @Bean
        AIActionRegistry actionRegistry() {
            AIActionRegistry registry = mock(AIActionRegistry.class);
            when(registry.getAllMetadata()).thenReturn(List.of(
                AIActionMetaData.builder()
                    .name("inspect_account")
                    .accessMode(ActionAccessMode.READ)
                    .groundingEligible(true)
                    .readActionResolutionEligible(true)
                    .build()
            ));
            return registry;
        }

        @Bean
        OrchestrationProperties orchestrationProperties() {
            OrchestrationProperties properties = new OrchestrationProperties();
            properties.setDefaultMode("resolver");
            OrchestrationProperties.ModeOverrides mode =
                new OrchestrationProperties.ModeOverrides();
            mode.setActionsEnabled(true);
            mode.setRetrievalEnabled(true);
            properties.getModes().put("resolver", mode);
            return properties;
        }

        @Bean
        OrchestrationPolicyResolutionStep orchestrationPolicyResolutionStep(
            OrchestrationProperties properties
        ) {
            return new OrchestrationPolicyResolutionStep(properties);
        }

        @Bean
        EffectiveCapabilitiesResolver effectiveCapabilitiesResolver() {
            return new DefaultEffectiveCapabilitiesResolver();
        }

        @Bean
        Pipeline pipeline() {
            return mock(Pipeline.class);
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        AICoreService aiCoreService() {
            return mock(AICoreService.class);
        }

        @Bean
        StructuredJsonCallExecutor structuredJsonCallExecutor() {
            return mock(StructuredJsonCallExecutor.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
