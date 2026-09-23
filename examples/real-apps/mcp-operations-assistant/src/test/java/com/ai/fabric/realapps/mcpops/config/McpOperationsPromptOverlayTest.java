package com.ai.fabric.realapps.mcpops.config;

import ai.fabric.config.PromptBundleProperties;
import ai.fabric.prompt.ClasspathPromptTemplateStore;
import ai.fabric.prompt.PromptTemplateResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class McpOperationsPromptOverlayTest {

    @Test
    void operationsOverlayPrecedesTheOptimizedDefaultBundle() throws IOException {
        StandardEnvironment environment = loadEnvironment();
        PromptBundleProperties promptBundle = Binder.get(environment)
            .bind("ai.prompts.bundle", PromptBundleProperties.class)
            .orElseThrow(() -> new AssertionError("ai.prompts.bundle must bind from application.yml"));

        assertThat(promptBundle.candidateVersions())
            .containsExactly("v1-mcp-operations", "v1-default-optimized", "v1");

        PromptTemplateResolver resolver = new PromptTemplateResolver(
            new ClasspathPromptTemplateStore(new DefaultResourceLoader()),
            promptBundle
        );

        var compound = resolver.resolve("intent-extraction/compound", "system").template();
        assertThat(compound.key().version()).isEqualTo("v1-mcp-operations");
        assertThat(compound.template())
            .contains("MCP OPERATIONS DOMAIN RULES")
            .contains("get_sandbox_service_status")
            .contains("list_recent_sandbox_incidents")
            .contains("restart_sandbox_service")
            .contains("live MCP actions are the source of operational facts");

        var multiStep = resolver.resolve("intent-extraction/multi-step", "classify").template();
        assertThat(multiStep.key().version()).isEqualTo("v1-mcp-operations");
        assertThat(multiStep.template())
            .contains("MCP Operations domain override")
            .contains("get sandbox service status")
            .contains("list recent sandbox incidents")
            .contains("restart sandbox service")
            .contains("Highest priority outside the MCP Operations domain");

        assertThat(resolver.resolve("intent-extraction/compound", "user").template().key().version())
            .isEqualTo("v1");
    }

    private static StandardEnvironment loadEnvironment() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (PropertySource<?> source : loader.load("application", new ClassPathResource("application.yml"))) {
            environment.getPropertySources().addFirst(source);
        }
        return environment;
    }
}
