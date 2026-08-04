package com.ai.fabric.realapps.behavior.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.fabric.execution.specialist.RegisteredSpecialist;
import ai.fabric.execution.specialist.SpecialistDefinitionSource;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.provider.AIProvider;
import ai.fabric.provider.ProviderStatus;
import com.ai.fabric.realapps.behavior.service.DurableBehaviorAnalysisService;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class BehaviorHealthContributorTest {

    @Test
    void exposesSafeSpecialistProviderAndDurabilityReadiness() throws Exception {
        SpecialistRegistry registry = mock(SpecialistRegistry.class);
        RegisteredSpecialist registration = mock(RegisteredSpecialist.class);
        when(registration.contentHash()).thenReturn("a".repeat(64));
        when(registration.source()).thenReturn(SpecialistDefinitionSource.MANIFEST);
        when(registry.findRegistered(DurableBehaviorAnalysisService.SPECIALIST_ID))
            .thenReturn(Optional.of(registration));

        AIProvider provider = mock(AIProvider.class);
        when(provider.getProviderName()).thenReturn("openai");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.getStatus()).thenReturn(
            ProviderStatus.builder().healthy(true).available(true).build()
        );

        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);

        MockEnvironment environment = new MockEnvironment()
            .withProperty("ai.providers.llm-provider", "openai")
            .withProperty(
                "app.behavior-demo.analysis.require-real-ai",
                "true"
            );

        Map<String, Object> details = new BehaviorHealthContributor(
            registry,
            List.of(provider),
            dataSource,
            environment
        ).details();

        assertThat(details.get("status")).isEqualTo("UP");
        assertThat(details.get("specialist")).isEqualTo(Map.of(
            "id",
            "behavior-risk-analyst@1",
            "ready",
            true,
            "contentHash",
            "a".repeat(64),
            "source",
            "MANIFEST"
        ));
        assertThat(details.get("provider")).isEqualTo(Map.of(
            "generation",
            "openai",
            "ready",
            true,
            "realProviderRequired",
            true,
            "realProviderSelected",
            true
        ));
        assertThat(details.get("execution")).isEqualTo(Map.of(
            "durability",
            "DURABLE",
            "automaticWrites",
            false,
            "inputBoundary",
            "SERVER_OWNED_USER_AND_RAW_EVENTS"
        ));
        assertThat(details).doesNotContainKeys(
            "prompt",
            "encryptionSecret",
            "fingerprintSecret"
        );
    }

    @Test
    void failsHealthWhenPublicPostureRequiresRealAiButLocalProviderIsSelected()
        throws Exception {
        SpecialistRegistry registry = mock(SpecialistRegistry.class);
        RegisteredSpecialist registration = mock(RegisteredSpecialist.class);
        when(registration.contentHash()).thenReturn("b".repeat(64));
        when(registration.source()).thenReturn(SpecialistDefinitionSource.MANIFEST);
        when(registry.findRegistered(DurableBehaviorAnalysisService.SPECIALIST_ID))
            .thenReturn(Optional.of(registration));

        AIProvider provider = mock(AIProvider.class);
        when(provider.getProviderName()).thenReturn("behavior-local");
        when(provider.isAvailable()).thenReturn(true);
        when(provider.getStatus()).thenReturn(
            ProviderStatus.builder().healthy(true).available(true).build()
        );

        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(1)).thenReturn(true);

        Map<String, Object> details = new BehaviorHealthContributor(
            registry,
            List.of(provider),
            dataSource,
            new MockEnvironment()
                .withProperty("ai.providers.llm-provider", "behavior-local")
                .withProperty(
                    "app.behavior-demo.analysis.require-real-ai",
                    "true"
                )
        ).details();

        assertThat(details.get("status")).isEqualTo("DOWN");
        assertThat(details.get("provider")).isEqualTo(Map.of(
            "generation",
            "behavior-local",
            "ready",
            true,
            "realProviderRequired",
            true,
            "realProviderSelected",
            false
        ));
    }
}
