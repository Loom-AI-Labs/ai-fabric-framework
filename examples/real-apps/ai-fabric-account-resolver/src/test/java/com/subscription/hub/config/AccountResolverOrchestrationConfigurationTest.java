package com.subscription.hub.config;

import ai.fabric.chat.config.ChatSessionProperties;
import ai.fabric.config.AIEntityConfigurationLoader;
import ai.fabric.config.AIProviderConfig;
import ai.fabric.config.OrchestrationProperties;
import ai.fabric.config.PromptBundleProperties;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AccountResolverOrchestrationConfigurationTest {

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void defaultsPinTheQualifiedAnswerSnapshotWithoutChangingRouting(String configFile) throws IOException {
        var environment = loadEnvironment(configFile);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        var providers = Binder.get(environment).bind("ai.providers", AIProviderConfig.class)
            .orElseThrow(() -> new AssertionError("ai.providers must bind from " + configFile));

        assertThat(providers.resolveOrchestrationLlmDefaults().model()).isEqualTo("gpt-4o-mini");
        assertThat(providers.resolveGenerationLlmDefaults().model()).isEqualTo("gpt-5.4-mini-2026-03-17");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application.yml", "application-prod.yml"})
    void answerGenerationModelDoesNotReplaceTheRoutingModel(String configFile) throws IOException {
        var environment = loadEnvironment(configFile);
        environment.getPropertySources().addFirst(new MapPropertySource("routing-model-test",
            Map.of("ai.providers.openai.model", "routing-model-fixture", "GENERATION_MODEL", "gpt-5.4-mini")));
        var providers = Binder.get(environment).bind("ai.providers", AIProviderConfig.class)
            .orElseThrow(() -> new AssertionError("ai.providers must bind from " + configFile));

        assertThat(providers.resolveOrchestrationLlmDefaults().model()).isEqualTo("routing-model-fixture");
        assertThat(providers.resolveGenerationLlmDefaults().model()).isEqualTo("gpt-5.4-mini");
        assertThat(providers.resolveGenerationLlmDefaults().providerName()).isEqualTo("openai");
    }

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
        assertThat(resolver.getSemanticRecovery().getMissingVectorSpace())
            .isEqualTo(OrchestrationProperties.MissingVectorSpaceRecovery.BOUNDED_ALLOWED_FAN_OUT);
        assertThat(resolver.getRag().getFanoutEnabled()).isTrue();
        assertThat(resolver.getRag().getMaxSpaces()).isEqualTo(2);
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
            .contains("Set requiresRetrieval, requiresGeneration, and requiresTargetResolution explicitly for every intent")
            .contains("For every INFORMATION intent, set needsAdvancedRAG explicitly, regardless of requiresGeneration")
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
            .contains("Set requiresRetrieval, requiresGeneration, and requiresTargetResolution explicitly for every intent")
            .contains("For every INFORMATION intent, set needsAdvancedRAG explicitly, regardless of requiresGeneration")
            .contains("ok add it")
            .contains("Generic \"it/that\" target-resolution rules apply only to external attached or pinned records");

        assertThat(resolver.resolve("intent-extraction/compound", "user").template().key().version())
            .isEqualTo("v1");

        var completion = resolver.resolve("intent-extraction/completion", "system-addon").template();
        assertThat(completion.key().version()).isEqualTo("v1-account-resolver");
        assertThat(completion.template()).contains("ACCOUNT RESOLVER COMPLETION SCOPE", "requiresTargetResolution=false",
            "Include needsAdvancedRAG explicitly on every INFORMATION intent", "preserving every user obligation and all already-valid fields");

        var ragAnswer = resolver.resolve("rag/generation", "answer").template();
        assertThat(ragAnswer.key().version()).isEqualTo("v1-account-resolver");
        assertThat(ragAnswer.template()).contains("{{query}}", "{{context}}")
            .doesNotContain("ACCOUNT RESOLVER ANSWER RULES", "ACCOUNT RESOLVER DECISION FIRST");
        var answerPolicy = resolver.resolve("rag/generation", "system").template();
        assertThat(answerPolicy.key().version()).isEqualTo("v1-account-resolver");
        assertThat(answerPolicy.template()).doesNotContain("{{query}}", "{{context}}")
            .contains("ACCOUNT RESOLVER DECISION FIRST")
            .contains("If any required check is unsatisfied, the account is not ready")
            .contains("Payment verification and address validation do not establish identity verification")
            .contains("ACCOUNT RESOLVER ANSWER RULES")
            .contains("current live account facts")
            .contains("billingAddressValidated=true")
            .contains("If either is false, the required billing address is missing or unvalidated")
            .contains("The wording of the user's question is not evidence of a blocker")
            .contains("do not turn unknown into failed or satisfied")
            .contains("no account blocker");
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
