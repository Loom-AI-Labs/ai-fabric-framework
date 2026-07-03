package com.subscription.hub.config;

import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.OrchestrationProperties;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResolverOrchestrationConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void resolverModeBindsToTypedFrameworkProperties(String configFile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> source : loader.load(configFile, new ClassPathResource(configFile))) {
            environment.getPropertySources().addFirst(source);
        }

        OrchestrationProperties properties = Binder.get(environment)
            .bind("ai.orchestration", OrchestrationProperties.class)
            .orElseThrow(() -> new AssertionError("ai.orchestration must bind from " + configFile));

        OrchestrationProperties.ModeOverrides resolver = properties.getModes().get("resolver");
        assertThat(properties.getDefaultMode()).isEqualTo("resolver");
        assertThat(properties.isStrictModeRouting()).isTrue();
        assertThat(resolver).isNotNull();
        assertThat(resolver.getActionsEnabled()).isTrue();
        assertThat(resolver.getActionsPreferred()).isTrue();
        assertThat(resolver.getRetrievalEnabled()).isTrue();
        assertThat(resolver.getRetrievalAllowlistRequired()).isTrue();
        assertThat(resolver.getInformationMode())
            .isEqualTo(OrchestrationProperties.InformationMode.DETERMINISTIC_RAG_GENERATE);
        assertThat(resolver.getRag().getRetrievalVectorSpacesAllowlist())
            .containsExactly("account-resolution-policy", "subscription-plan");
        assertThat(resolver.getReadActionResolution().getEnabled()).isTrue();
        assertThat(resolver.getReadActionResolution().getPlanningMode())
            .isEqualTo(OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE);
        assertThat(resolver.getReadActionResolution().getAllowedReadActions())
            .containsExactly("inspect_account_readiness");
        assertThat(resolver.getReadActionResolution().getMaxIterations()).isEqualTo(2);
        assertThat(resolver.getReadActionResolution().getMaxTotalActions()).isEqualTo(2);
        assertThat(resolver.getReadActionResolution().getRagCooperationMode())
            .isEqualTo(OrchestrationProperties.ReadActionResolutionRagCooperationMode.PARALLEL_ACTIONS_AND_RAG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"account-resolution-policy", "subscription-plan"})
    void resolverRagVectorSpacesAreKnownEntityTypes(String entityType) {
        AIEntityConfigurationLoader loader = new AIEntityConfigurationLoader(new DefaultResourceLoader());
        loader.loadConfigurationFromFile("ai-entity-config.yml");

        assertThat(loader.getSupportedEntityTypes()).contains(entityType);
    }
}
