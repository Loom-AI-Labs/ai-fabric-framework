package com.ai.fabric.runtime;

import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.auth.RuntimePublicTokenService;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimePublicTokenServiceTest {

    @Test
    void validateBearerTokenAcceptsAuthenticatedTokenWithConfiguredIssuerAndAudience() {
        RuntimeAuthProperties properties = baseProperties();
        RuntimePublicTokenService tokenService = new RuntimePublicTokenService(properties);

        RuntimePublicTokenService.IssuedPublicRuntimeToken issued = tokenService.issueAuthenticatedToken(
            "customer-123",
            RuntimeAuthSubjectType.END_USER,
            "session-123",
            "dep-public",
            "cus-public",
            "ten-public",
            List.of("chat:query", "chat:conversations"),
            "commerce-app",
            List.of("storefront-chat")
        );

        RuntimeAuthContext authContext = tokenService.validateBearerToken("Bearer " + issued.token());

        assertThat(authContext.getAuthMode()).isEqualTo(RuntimeAuthMode.PUBLIC_RUNTIME_AUTHENTICATED);
        assertThat(authContext.getSubjectId()).isEqualTo("customer-123");
        assertThat(authContext.getAudiences()).containsExactly("storefront-chat");
        assertThat(authContext.getIssuer()).isEqualTo("commerce-app");
    }

    @Test
    void validateBearerTokenRejectsWrongIssuer() {
        RuntimeAuthProperties properties = baseProperties();
        RuntimePublicTokenService tokenService = new RuntimePublicTokenService(properties);

        String token = tokenService.issueAuthenticatedToken(
            "customer-123",
            RuntimeAuthSubjectType.END_USER,
            "session-123",
            "dep-public",
            "cus-public",
            "ten-public",
            List.of("chat:query"),
            "unknown-issuer",
            List.of("storefront-chat")
        ).token();

        assertThatThrownBy(() -> tokenService.validateBearerToken("Bearer " + token))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void validateBearerTokenRejectsWrongAudience() {
        RuntimeAuthProperties properties = baseProperties();
        RuntimePublicTokenService tokenService = new RuntimePublicTokenService(properties);

        String token = tokenService.issueAuthenticatedToken(
            "customer-123",
            RuntimeAuthSubjectType.END_USER,
            "session-123",
            "dep-public",
            "cus-public",
            "ten-public",
            List.of("chat:query"),
            "commerce-app",
            List.of("different-audience")
        ).token();

        assertThatThrownBy(() -> tokenService.validateBearerToken("Bearer " + token))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void issueAuthenticatedTokenRejectsScopesOutsideConfiguredAllowlist() {
        RuntimeAuthProperties properties = baseProperties();
        RuntimePublicTokenService tokenService = new RuntimePublicTokenService(properties);

        assertThatThrownBy(() -> tokenService.issueAuthenticatedToken(
            "customer-123",
            RuntimeAuthSubjectType.END_USER,
            "session-123",
            "dep-public",
            "cus-public",
            "ten-public",
            List.of("chat:query", "chat:admin"),
            "commerce-app",
            List.of("storefront-chat")
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("configured allowlist");
    }

    @Test
    void validateBearerTokenRejectsAnonymousTokenWithScopesOutsideConfiguredAnonymousPolicy() {
        RuntimeAuthProperties issuingProperties = baseProperties();
        RuntimePublicTokenService issuingService = new RuntimePublicTokenService(issuingProperties);
        String token = issuingService.issueAnonymousToken().token();

        RuntimeAuthProperties validatingProperties = baseProperties();
        validatingProperties.getPublicTokens().setAnonymousGrantedScopes(List.of("chat:query"));
        RuntimePublicTokenService validatingService = new RuntimePublicTokenService(validatingProperties);

        assertThatThrownBy(() -> validatingService.validateBearerToken("Bearer " + token))
            .isInstanceOf(ResponseStatusException.class)
            .extracting(ex -> ((ResponseStatusException) ex).getStatusCode())
            .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void issueAnonymousTokenUsesConfiguredDefaultAudience() {
        RuntimeAuthProperties properties = baseProperties();
        RuntimePublicTokenService tokenService = new RuntimePublicTokenService(properties);

        RuntimeAuthContext authContext = tokenService.issueAnonymousToken().authContext();

        assertThat(authContext.getAuthMode()).isEqualTo(RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS);
        assertThat(authContext.getSubjectId()).startsWith("anon-");
        assertThat(authContext.getSessionId()).isEqualTo(authContext.getSubjectId());
        assertThat(authContext.getAudiences()).containsExactly("storefront-chat");
        assertThat(authContext.getGrantedScopes()).containsExactly("chat:query", "chat:suggestions", "chat:conversations");
    }

    private RuntimeAuthProperties baseProperties() {
        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getPublicTokens().setSigningKey("public-secret");
        properties.getPublicTokens().setIssuer("runtime-public-test");
        properties.getPublicTokens().setAcceptedIssuers(List.of("runtime-public-test", "commerce-app"));
        properties.getPublicTokens().setAcceptedAudiences(List.of("storefront-chat"));
        properties.getPublicTokens().setDefaultAudience("storefront-chat");
        properties.getPublicTokens().getBootstrap().setEnabled(true);
        properties.getPublicTokens().getDefaults().setDeploymentId("dep-public");
        properties.getPublicTokens().getDefaults().setCustomerId("cus-public");
        properties.getPublicTokens().getDefaults().setTenantId("ten-public");
        return properties;
    }
}
