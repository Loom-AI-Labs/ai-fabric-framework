package com.ai.fabric.realapps.agenticresolver.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.action.ActionProposalReceiptRepository;
import ai.fabric.execution.config.AIExecutionProperties;
import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.plan.AIExecutionCoordinator;
import ai.fabric.execution.plan.ExecutionPlanRegistry;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.manifest.SpecialistManifestRuntimeStatus;
import ai.fabric.execution.state.DurableExecutionRepository;
import ai.fabric.provider.AIProvider;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialists;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentInfoServiceTest {

    @Test
    void healthIncludesDeploymentMetadataFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("APP_VERSION", "1.2.3")
            .withProperty("AI_FABRIC_VERSION", "0.4.0")
            .withProperty("APP_BUILD_COMMIT", "unknown")
            .withProperty("git_commit", "abc1234")
            .withProperty("APP_BUILD_BRANCH", "unknown")
            .withProperty("git_branch", "main")
            .withProperty("APP_BUILD_TIME", "2026-07-03T12:00:00Z");

        AIProvider provider = mock(AIProvider.class);
        when(provider.getProviderName()).thenReturn("openai");
        when(provider.isAvailable()).thenReturn(true);
        SpecialistRegistry registry = mock(SpecialistRegistry.class);
        SpecialistDefinition<?, ?> definition = mock(SpecialistDefinition.class);
        SpecialistDefinition<?, ?> readDefinition = mock(
            SpecialistDefinition.class
        );
        SpecialistDefinition<?, ?> coordinatorDefinition = mock(
            SpecialistDefinition.class
        );
        SpecialistDefinition<?, ?> intakeDefinition = mock(
            SpecialistDefinition.class
        );
        when(definition.id()).thenReturn(
            AccountResolverSpecialists.SPECIALIST_ID
        );
        when(readDefinition.id()).thenReturn(
            AccountResolverSpecialists.READ_SPECIALIST_ID
        );
        when(coordinatorDefinition.id()).thenReturn(
            AccountResolverSpecialists.DELEGATION_COORDINATOR_ID
        );
        when(intakeDefinition.id()).thenReturn(
            AccountResolverSpecialists.HANDOFF_INTAKE_ID
        );
        when(registry.list()).thenReturn(List.of(
            definition,
            readDefinition,
            coordinatorDefinition,
            intakeDefinition
        ));
        when(registry.listRegistered()).thenReturn(List.of(
            registered(definition),
            registered(readDefinition),
            registered(coordinatorDefinition),
            registered(intakeDefinition)
        ));
        AIExecutionProperties executionProperties = new AIExecutionProperties();
        executionProperties.getReceipts().setEnabled(true);
        executionProperties.getReceipts().setRepository(
            AIExecutionProperties.ReceiptRepository.JDBC
        );
        executionProperties.getReceipts().setCleanupEnabled(true);
        executionProperties.getReceipts().setRetention(Duration.ofDays(30));
        executionProperties.getAsync().setRepository(
            AIExecutionProperties.AsyncRepository.JDBC
        );
        Map<String, Object> health = new DeploymentInfoService(
            environment,
            List.of(provider),
            mock(AIExecutionGateway.class),
            mock(AIExecutionCoordinator.class),
            emptyPlanRegistry(),
            registry,
            mock(ActionProposalReceiptRepository.class),
            Optional.of(mock(DurableExecutionRepository.class)),
            executionProperties,
            new SpecialistManifestRuntimeStatus(
                true,
                true,
                4,
                4,
                0,
                "b".repeat(64),
                List.of()
            )
        ).health();

        assertThat(health)
            .containsEntry("status", "UP")
            .containsEntry("service", "agentic-ai-action-resolver")
            .containsEntry("version", "1.2.3")
            .containsEntry("aiFabricVersion", "0.4.0")
            .containsEntry("commit", "abc1234")
            .containsEntry("branch", "main")
            .containsEntry("builtAt", "2026-07-03T12:00:00Z");
        assertThat(health.get("startedAt")).isInstanceOf(String.class);
        assertThat(health.get("checkedAt")).isInstanceOf(String.class);
        assertThat(health.get("providerReadiness"))
            .isEqualTo(Map.of(
                "ready", true,
                "configuredProviders", List.of("openai"),
                "availableProviders", List.of("openai")
            ));
        assertThat(health.get("execution"))
            .isInstanceOfSatisfying(Map.class, execution ->
                assertThat(execution)
                    .containsEntry("ready", true)
                    .containsEntry("planCoordinatorReady", true)
                    .containsEntry("planDurability", "EPHEMERAL")
                    .containsEntry("plans", List.of())
                    .containsEntry("asyncDurability", "DURABLE")
                    .containsEntry("durableAsyncStateReady", true)
                    .containsEntry("writeReceiptDurability", "JDBC")
                    .containsEntry("writeReceiptsReady", true)
                    .containsEntry("receiptTtl", "PT10M")
                    .containsEntry("staleExecutingAfter", "PT2M")
                    .containsEntry("receiptCleanupEnabled", true)
                    .containsEntry("receiptRetention", "PT720H")
                    .containsEntry("accountResolverRegistered", true)
                    .containsEntry("accountResolverReadRegistered", true)
                    .containsEntry(
                        "accountResolutionCoordinatorRegistered",
                        true
                    )
                    .containsEntry(
                        "accountResolutionIntakeRegistered",
                        true
                    )
                    .containsEntry("proactiveEventExecution", Map.of(
                        "ready", true,
                        "eventType", "PAYMENT_VERIFICATION_FAILED",
                        "source", "EVENT",
                        "principalType", "SERVICE",
                        "durability", "DURABLE",
                        "automaticMutation", false
                    ))
            );
    }

    private ExecutionPlanRegistry emptyPlanRegistry() {
        ExecutionPlanRegistry registry = mock(ExecutionPlanRegistry.class);
        when(registry.list()).thenReturn(List.of());
        return registry;
    }

    private RegisteredSpecialist registered(
        SpecialistDefinition<?, ?> definition
    ) {
        return new RegisteredSpecialist(
            definition,
            SpecialistDefinitionSource.MANIFEST,
            "a".repeat(64),
            "test.yml#1",
            Map.of()
        );
    }
}
