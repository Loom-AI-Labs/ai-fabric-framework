package ai.fabric.execution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.config.OrchestrationProperties;
import ai.fabric.core.AICoreService;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.gateway.AIExecutionConversationRecorder;
import ai.fabric.chat.service.ChatSessionService;
import ai.fabric.execution.gateway.ExecutionCapabilityInventory;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.intent.action.AIActionMetaData;
import ai.fabric.intent.action.AIActionRegistry;
import ai.fabric.intent.action.ActionAccessMode;
import ai.fabric.intent.orchestration.capability.DefaultEffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.capability.EffectiveCapabilitiesResolver;
import ai.fabric.intent.orchestration.pipeline.Pipeline;
import ai.fabric.intent.orchestration.pipeline.steps.OrchestrationPolicyResolutionStep;
import ai.fabric.llm.structured.StructuredJsonCallExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
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
                assertThat(context).hasSingleBean(SpecialistRegistry.class);
                assertThat(context).hasSingleBean(ExecutionCapabilityInventory.class);
                assertThat(context)
                    .getBean(ExecutionCapabilityInventory.class)
                    .satisfies(inventory -> {
                        assertThat(inventory.registeredVectorSpaces())
                            .containsExactly("account-policy");
                        assertThat(inventory.deploymentAllowedActions())
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
