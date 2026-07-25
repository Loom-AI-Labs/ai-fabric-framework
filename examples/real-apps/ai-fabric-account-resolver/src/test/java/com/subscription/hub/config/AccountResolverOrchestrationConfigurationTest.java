package com.subscription.hub.config;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptTemplateResolver;
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
        StandardEnvironment environment = loadEnvironment(configFile);

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
        assertThat(resolver.getRag().getSimilarityThreshold()).isEqualTo(0.45);
        assertThat(resolver.getReadActionResolution().getEnabled()).isTrue();
        assertThat(resolver.getReadActionResolution().getPlanningMode())
            .isEqualTo(OrchestrationProperties.ReadActionResolutionPlanningMode.ITERATIVE);
        assertThat(resolver.getReadActionResolution().getAllowedReadActions())
            .containsExactly("get_account_profile");
        assertThat(resolver.getReadActionResolution().getMaxIterations()).isEqualTo(2);
        assertThat(resolver.getReadActionResolution().getMaxTotalActions()).isEqualTo(2);
        assertThat(resolver.getReadActionResolution().getRagCooperationMode())
            .isEqualTo(OrchestrationProperties.ReadActionResolutionRagCooperationMode.PARALLEL_ACTIONS_AND_RAG);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void frameworkChatSessionBindsForResolverConversationMemory(String configFile) throws IOException {
        StandardEnvironment environment = loadEnvironment(configFile);

        ChatSessionProperties properties = Binder.get(environment)
            .bind("ai.chat", ChatSessionProperties.class)
            .orElseThrow(() -> new AssertionError("ai.chat must bind from " + configFile));

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.isAutoCreateSessions()).isTrue();
        assertThat(properties.getWindowSize()).isEqualTo(8);
        assertThat(properties.getMaxContextChars()).isEqualTo(4000);
        assertThat(properties.getPinnedTargetReuseWindowTurns()).isEqualTo(3);
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void resolverPromptOverlayBindsAndFallsBackToDefaultBundle(String configFile) throws IOException {
        StandardEnvironment environment = loadEnvironment(configFile);

        PromptBundleProperties promptBundle = Binder.get(environment)
            .bind("ai.prompts.bundle", PromptBundleProperties.class)
            .orElseThrow(() -> new AssertionError("ai.prompts.bundle must bind from " + configFile));

        assertThat(promptBundle.getOverlays()).containsExactly("v1-account-resolver");
        assertThat(promptBundle.candidateVersions()).containsExactly("v1-account-resolver", "v1");

        PromptTemplateResolver resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            promptBundle
        );

        var compoundSystem = resolver.resolve("intent-extraction/compound", "system").template();
        assertThat(compoundSystem.key().version()).isEqualTo("v1-account-resolver");
        assertThat(compoundSystem.template())
            .contains("ACCOUNT RESOLVER OPERATING PRINCIPLES")
            .contains("This assistant resolves the current authenticated user's account")
            .contains("Do not ask the user for internal identifiers")
            .contains("Treat policy documents as human-readable guidance")
            .contains("For account-owned workflows, set requiresTargetResolution=false")
            .contains("ACCOUNT RESOLVER REFERENCE RESOLUTION")
            .contains("ok add it")
            .contains("Generic \"it/that\" target-resolution rules apply only to external attached or pinned records");

        var multiStepClassify = resolver.resolve("intent-extraction/multi-step", "classify").template();
        assertThat(multiStepClassify.key().version()).isEqualTo("v1-account-resolver");
        assertThat(multiStepClassify.template())
            .contains("Account Resolver resolves the current authenticated user's account")
            .contains("Do not ask the user for internal identifiers")
            .contains("Treat policy documents as human-readable guidance")
            .contains("For account-owned workflows, set requiresTargetResolution=false")
            .contains("Account Resolver reference resolution")
            .contains("ok add it")
            .contains("Generic \"it/that\" target-resolution rules apply only to external attached or pinned records");

        assertThat(resolver.resolve("intent-extraction/compound", "user").template().key().version())
            .isEqualTo("v1");

        var ragAnswer = resolver.resolve("rag/generation", "answer").template();
        assertThat(ragAnswer.key().version()).isEqualTo("v1-account-resolver");
        assertThat(ragAnswer.template())
            .contains("ACCOUNT RESOLVER ANSWER RULES")
            .contains("live account profile facts")
            .contains("billingAddressValidated=false");
    }

    @ParameterizedTest
    @ValueSource(strings = {"account-resolution-policy", "subscription-plan"})
    void resolverRagVectorSpacesAreKnownEntityTypes(String entityType)
        throws IOException {
        AIEntityConfigurationLoader loader =
            new AIEntityConfigurationLoader(
                loadEnvironment("ai-entity-config.yml")
            );
        loader.loadConfiguration();

        assertThat(loader.getSupportedEntityTypes()).contains(entityType);
    }

    private static StandardEnvironment loadEnvironment(String configFile) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> source : loader.load(configFile, new ClassPathResource(configFile))) {
            environment.getPropertySources().addFirst(source);
        }
        return environment;
    }
}
