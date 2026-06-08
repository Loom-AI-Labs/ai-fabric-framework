package com.ai.fabric.runtime.auth;

import com.ai.fabric.runtime.RuntimePrivateAssertionTestSupport;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuntimeRequestAuthResolverTest {

    @Test
    void privateAssertionAuthPassesWhenIssuerAndAudienceMatchConfiguredPolicy() {
        RuntimeRequestAuthResolver resolver = new RuntimeRequestAuthResolver(strictProperties());
        MockHttpServletRequest request = verifiedRequest("trusted-backend-app", "dep-123,chat-surface");

        RuntimeResolvedIdentity identity = resolver.resolveVerifiedForChat(request);

        assertThat(identity.getAuthContext().getSubjectId()).isEqualTo("customer-123");
        assertThat(identity.getAuthContext().getIssuer()).isEqualTo("trusted-backend-app");
        assertThat(identity.getAuthContext().getAudiences()).containsExactly("dep-123", "chat-surface");
    }

    @Test
    void privateAssertionAuthRejectsUntrustedIssuer() {
        RuntimeRequestAuthResolver resolver = new RuntimeRequestAuthResolver(strictProperties());
        MockHttpServletRequest request = verifiedRequest("rogue-backend", "dep-123");

        assertThatThrownBy(() -> resolver.resolveVerifiedForChat(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Runtime verified auth issuer is not allowed");
    }

    @Test
    void privateAssertionAuthRejectsMissingAudienceWhenPolicyConfigured() {
        RuntimeRequestAuthResolver resolver = new RuntimeRequestAuthResolver(strictProperties());
        MockHttpServletRequest request = verifiedRequest("trusted-backend-app", null);

        assertThatThrownBy(() -> resolver.resolveVerifiedForChat(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Runtime verified auth audience is not allowed");
    }

    @Test
    void privateAssertionAuthRejectsNonMatchingAudience() {
        RuntimeRequestAuthResolver resolver = new RuntimeRequestAuthResolver(strictProperties());
        MockHttpServletRequest request = verifiedRequest("trusted-backend-app", "other-deployment");

        assertThatThrownBy(() -> resolver.resolveVerifiedForChat(request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Runtime verified auth audience is not allowed");
    }

    private RuntimeAuthProperties strictProperties() {
        RuntimeAuthProperties properties = RuntimePrivateAssertionTestSupport.strictPrivateRuntimeProperties();
        properties.getIngress().setAcceptedIssuers(java.util.List.of("trusted-backend-app"));
        properties.getIngress().setAcceptedAudiences(java.util.List.of("dep-123"));
        return properties;
    }

    private MockHttpServletRequest verifiedRequest(String issuer, String audiences) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RuntimePrivateAssertionTestSupport.addPrivateRuntimeHeaders(
            request,
            strictProperties(),
            RuntimeAuthContext.builder()
                .subjectId("customer-123")
                .subjectType(RuntimeAuthSubjectType.END_USER)
                .authMode(RuntimeAuthMode.PRIVATE_RUNTIME_BACKEND_MEDIATED)
                .callerType(RuntimeAuthCallerType.TRUSTED_BACKEND)
                .sessionId("session-123")
                .deploymentId("dep-123")
                .issuer(issuer)
                .audiences(audiences == null ? java.util.List.of() : java.util.Arrays.stream(audiences.split(",")).map(String::trim).toList())
                .expiresAt(Instant.now().plusSeconds(300))
                .build()
        );
        return request;
    }
}
