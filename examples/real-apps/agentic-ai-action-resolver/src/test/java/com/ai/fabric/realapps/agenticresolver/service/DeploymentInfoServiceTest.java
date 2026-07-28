package com.ai.fabric.realapps.agenticresolver.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.gateway.AIExecutionGateway;
import ai.fabric.execution.specialist.SpecialistDefinition;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.provider.AIProvider;
import com.ai.fabric.realapps.agenticresolver.agentic.AccountResolverSpecialistConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeploymentInfoServiceTest {

    @Test
    void healthIncludesDeploymentMetadataFromEnvironment() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty("APP_VERSION", "1.2.3")
            .withProperty("AI_FABRIC_VERSION", "0.3.3")
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
        when(definition.id()).thenReturn(
            AccountResolverSpecialistConfiguration.SPECIALIST_ID
        );
        when(registry.list()).thenReturn(List.of(definition));
        Map<String, Object> health = new DeploymentInfoService(
            environment,
            List.of(provider),
            mock(AIExecutionGateway.class),
            registry
        ).health();

        assertThat(health)
            .containsEntry("status", "UP")
            .containsEntry("service", "agentic-ai-action-resolver")
            .containsEntry("version", "1.2.3")
            .containsEntry("aiFabricVersion", "0.3.3")
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
                    .containsEntry("asyncDurability", "EPHEMERAL")
                    .containsEntry("accountResolverRegistered", true)
            );
    }
}
