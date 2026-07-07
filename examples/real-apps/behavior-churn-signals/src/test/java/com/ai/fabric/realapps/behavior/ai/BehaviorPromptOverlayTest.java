package com.ai.fabric.realapps.behavior.ai;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BehaviorPromptOverlayTest {

    @Test
    void resolvesBehaviorSignalsOverlayBeforeBasePromptBundle() {
        PromptBundleProperties properties = new PromptBundleProperties();
        properties.setOverlays(List.of("v1-behavior-signals"));
        PromptTemplateResolver resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            properties
        );

        var system = resolver.resolve("behavior/analysis", "system").template();
        var user = resolver.resolve("behavior/analysis", "user").template();
        var repair = resolver.resolve("structured-json/repair", "system-addon").template();

        assertThat(system.key().version()).isEqualTo("v1-behavior-signals");
        assertThat(system.template())
            .contains("AI behavior analyst")
            .contains("Return only valid JSON")
            .contains("ENGINEERING_ESCALATION")
            .contains("reverse an earlier healthy baseline");
        assertThat(user.key().version()).isEqualTo("v1-behavior-signals");
        assertThat(user.template())
            .contains("insights.action_family")
            .contains("Prefer this over ADOPTION_HELP");
        assertThat(repair.key().version()).isEqualTo("v1-behavior-signals");
        assertThat(repair.template()).contains("strict JSON parser");
    }
}
