package com.ai.fabric.runtime.config;

import com.ai.fabric.runtime.auth.RuntimeAuthIngressMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;

class RuntimeAuthStartupValidatorTest {

    @Test
    void failsWhenVerifiedContextRequiredWithoutTrustedBackendCredential() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);

        RuntimeAuthStartupValidator validator = new RuntimeAuthStartupValidator(properties);

        assertThat(validator.validationErrors())
            .anyMatch(message -> message.contains("VERIFIED_CONTEXT_REQUIRED"))
            .anyMatch(message -> message.contains("trusted backend API key"));
    }

    @Test
    void failsWhenVerifiedContextRequiredWithoutIssuerOrAudiencePolicy() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        properties.getIngress().getTrustedBackend().setApiKeyValue("trusted-backend-key");
        properties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-key");

        RuntimeAuthStartupValidator validator = new RuntimeAuthStartupValidator(properties);

        assertThat(validator.validationErrors())
            .anyMatch(message -> message.contains("ingress.accepted-issuers"))
            .anyMatch(message -> message.contains("ingress.accepted-audiences"));
    }

    @Test
    void failsWhenPublicRuntimeConfiguredWithoutIssuerOrAudiencePolicy() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getPublicTokens().setSigningKey("test-signing-key");

        RuntimeAuthStartupValidator validator = new RuntimeAuthStartupValidator(properties);

        assertThat(validator.validationErrors())
            .anyMatch(message -> message.contains("accepted-issuers"))
            .anyMatch(message -> message.contains("accepted-audiences"));
    }

    @Test
    void failsWhenBootstrapEnabledWithoutSigningKeyAndStillWarnsOnLooseOriginPolicy() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getPublicTokens().getBootstrap().setEnabled(true);
        properties.getPublicTokens().getBootstrap().setAllowMissingOrigin(true);

        RuntimeAuthStartupValidator validator = new RuntimeAuthStartupValidator(properties);

        assertThat(validator.validationErrors())
            .anyMatch(message -> message.contains("POST /api/public/chat/session"));
        assertThat(validator.validationWarnings())
            .anyMatch(message -> message.contains("allowed origins"))
            .anyMatch(message -> message.contains("allow-missing-origin=true"));
    }

    @Test
    void staysQuietForFullyConfiguredStrictRuntimePosture() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        properties.getIngress().getTrustedBackend().setApiKeyValue("trusted-backend-key");
        properties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-key");
        properties.getIngress().setAcceptedIssuers(java.util.List.of("platform-poc:SESSION"));
        properties.getIngress().setAcceptedAudiences(java.util.List.of("dep-auth"));
        properties.getPublicTokens().setSigningKey("test-signing-key");
        properties.getPublicTokens().setAcceptedIssuers(java.util.List.of("runtime-public-bootstrap"));
        properties.getPublicTokens().setAcceptedAudiences(java.util.List.of("dep-auth"));
        properties.getPublicTokens().getBootstrap().setEnabled(true);
        properties.getPublicTokens().getBootstrap().setAllowedOrigins(java.util.List.of("https://store.example"));

        RuntimeAuthStartupValidator validator = new RuntimeAuthStartupValidator(properties);

        assertThat(validator.validationErrors()).isEmpty();
        assertThat(validator.validationWarnings()).isEmpty();
    }

    @Test
    void startupValidatorThrowsWhenFatalAuthErrorsRemain() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);

        RuntimeAuthStartupValidator validator = new RuntimeAuthStartupValidator(properties);

        assertThatThrownBy(validator::afterSingletonsInstantiated)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("trusted backend API key")
            .hasMessageContaining("accepted-issuers")
            .hasMessageContaining("accepted-audiences");
    }
}
