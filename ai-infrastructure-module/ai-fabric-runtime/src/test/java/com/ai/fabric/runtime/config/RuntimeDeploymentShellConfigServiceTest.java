package com.ai.fabric.runtime.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeDeploymentShellConfigServiceTest {

    @Test
    void loadWithoutConfiguredFileKeepsDefaults() {
        RuntimeDeploymentShellConfigProperties properties = new RuntimeDeploymentShellConfigProperties();
        RuntimeDeploymentShellConfigService service = new RuntimeDeploymentShellConfigService(
            properties,
            new DefaultResourceLoader(),
            new ObjectMapper()
        );

        service.load();

        assertThat(service.currentContractVersion()).isEqualTo(RuntimeDeploymentShellConfigService.CONTRACT_VERSION);
        assertThat(service.currentModuleIds()).isEmpty();
        assertThat(service.currentCardIds()).isEmpty();
        assertThat(service.currentStarterPrompts()).isEmpty();
        assertThat(service.currentRoot().path("modules").isArray()).isTrue();
        assertThat(service.currentRoot().path("cards").isArray()).isTrue();
        assertThat(service.currentRoot().path("starterPrompts").isArray()).isTrue();
    }

    @Test
    void loadConfiguredFileExposesGreetingAndStarterPrompts() {
        RuntimeDeploymentShellConfigProperties properties = new RuntimeDeploymentShellConfigProperties();
        properties.setConfigFile("classpath:test-runtime-shell-config.json");
        RuntimeDeploymentShellConfigService service = new RuntimeDeploymentShellConfigService(
            properties,
            new DefaultResourceLoader(),
            new ObjectMapper()
        );

        service.load();

        assertThat(service.currentGreetingTitle()).isEqualTo("Commerce Assistant");
        assertThat(service.currentGreetingMessage()).isEqualTo("Ask about products, orders, or policy.");
        assertThat(service.currentModuleIds()).containsExactly("product-catalog", "orders");
        assertThat(service.currentCardIds()).containsExactly("product-list", "order-status");
        assertThat(service.currentStarterPromptCount()).isEqualTo(2);
        assertThat(service.currentStarterPrompts()).extracting(prompt -> prompt.path("label").asText())
            .containsExactly("Browse featured products", "Track my latest order");
    }

    @Test
    void loadLegacyFileWithoutContractVersionKeepsCompatibility() {
        RuntimeDeploymentShellConfigProperties properties = new RuntimeDeploymentShellConfigProperties();
        properties.setConfigFile("classpath:test-runtime-shell-config-legacy.json");
        RuntimeDeploymentShellConfigService service = new RuntimeDeploymentShellConfigService(
            properties,
            new DefaultResourceLoader(),
            new ObjectMapper()
        );

        service.load();

        assertThat(service.currentContractVersion()).isEqualTo(RuntimeDeploymentShellConfigService.CONTRACT_VERSION);
        assertThat(service.currentModuleIds()).containsExactly("product-catalog", "orders");
        assertThat(service.currentCardIds()).containsExactly("product-list");
        assertThat(service.currentGreetingTitle()).isNull();
        assertThat(service.currentGreetingMessage()).isEqualTo("Ask about products.");
        assertThat(service.currentStarterPromptCount()).isEqualTo(1);
        assertThat(service.currentStarterPrompts()).extracting(prompt -> prompt.path("label").asText())
            .containsExactly("Browse featured products");
        assertThat(service.currentStarterPrompts()).extracting(prompt -> prompt.path("moduleId").asText())
            .containsExactly("product-catalog");
    }

    @Test
    void loadRejectsUnsupportedModuleId() {
        RuntimeDeploymentShellConfigProperties properties = new RuntimeDeploymentShellConfigProperties();
        properties.setConfigFile("classpath:test-runtime-shell-config-invalid-module.json");
        RuntimeDeploymentShellConfigService service = new RuntimeDeploymentShellConfigService(
            properties,
            new DefaultResourceLoader(),
            new ObjectMapper()
        );

        assertThatThrownBy(service::load)
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Unsupported shell module id: custom-module");
    }

    @Test
    void loadRejectsUnsupportedContractVersion() {
        RuntimeDeploymentShellConfigProperties properties = new RuntimeDeploymentShellConfigProperties();
        properties.setConfigFile("classpath:test-runtime-shell-config-invalid-contract.json");
        RuntimeDeploymentShellConfigService service = new RuntimeDeploymentShellConfigService(
            properties,
            new DefaultResourceLoader(),
            new ObjectMapper()
        );

        assertThatThrownBy(service::load)
            .isInstanceOf(IllegalStateException.class)
            .hasRootCauseMessage("Unsupported deployment shell config contract version 'SHELL_CONFIG_V99' in classpath:test-runtime-shell-config-invalid-contract.json. Supported version: SHELL_CONFIG_V1");
    }
}
