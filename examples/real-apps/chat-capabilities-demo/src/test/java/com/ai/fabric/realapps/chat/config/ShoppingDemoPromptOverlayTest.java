package com.ai.fabric.realapps.chat.config;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ShoppingDemoPromptOverlayTest {

    @Test
    void applicationConfigEnablesShoppingDemoOverlayBeforeCommerce() throws Exception {
        PropertySource<?> source = new YamlPropertySourceLoader()
            .load("application", new ClassPathResource("application.yml"))
            .getFirst();

        assertThat(source.getProperty("ai.prompts.bundle.overlays[0]")).isEqualTo("v1-shopping-demo");
        assertThat(source.getProperty("ai.prompts.bundle.overlays[1]")).isEqualTo("v1-commerce");
    }

    @Test
    void resolvesShoppingDemoIntentPromptsBeforeCommercePack() {
        PromptBundleProperties bundle = new PromptBundleProperties();
        bundle.setOverlays(List.of("v1-shopping-demo", "v1-commerce"));

        PromptTemplateResolver resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            bundle
        );

        var compoundSystem = resolver.resolve("intent-extraction/compound", "system").template();
        assertThat(compoundSystem.key().version()).isEqualTo("v1-shopping-demo");
        assertThat(compoundSystem.template())
            .contains("RECENT CONVERSATION FOLLOW-UPS")
            .contains("better for gaming?")
            .contains("latest relevant exchange, up to the last 3 user/assistant messages");

        var compoundManaged = resolver.resolve("intent-extraction/compound", "system-managed").template();
        assertThat(compoundManaged.key().version()).isEqualTo("v1-shopping-demo");
        assertThat(compoundManaged.template())
            .contains("RECENT CONVERSATION FOLLOW-UPS")
            .contains("better for gaming?");

        var multiStepClassify = resolver.resolve("intent-extraction/multi-step", "classify").template();
        assertThat(multiStepClassify.key().version()).isEqualTo("v1-shopping-demo");
        assertThat(multiStepClassify.template())
            .contains("Recent conversation follow-ups")
            .contains("shopper is continuing to ask about")
            .contains("I can help with this store's products, policies, comparisons, cart, and approved order help.");
    }
}
