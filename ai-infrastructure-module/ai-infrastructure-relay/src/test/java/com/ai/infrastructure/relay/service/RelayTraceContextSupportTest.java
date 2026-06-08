package com.ai.infrastructure.relay.service;

import com.ai.infrastructure.relay.api.TraceContextDto;
import com.ai.infrastructure.relay.api.VerifiedAuthContextDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RelayTraceContextSupportTest {

    @Test
    void verifiedAuthContextIsPreferredForForwardHeadersAndRateLimiting() {
        TraceContextDto trace = new TraceContextDto(
            "req_1",
            "chat_1",
            new VerifiedAuthContextDto(
                "verified-user",
                "END_USER",
                "PUBLIC_RUNTIME_AUTHENTICATED",
                "PUBLIC_BROWSER",
                "verified-session",
                "dep-123",
                "cus-123",
                "ten-123",
                "commerce-app",
                "2026-04-07T01:45:00Z",
                List.of("chat:query", "chat:actions"),
                List.of("storefront-chat")
            )
        );

        Map<String, String> headers = RelayTraceContextSupport.forwardHeaders(trace);

        assertThat(headers)
            .containsEntry("X-AIFABRIC-REQUEST-ID", "req_1")
            .containsEntry("X-AIFABRIC-CONVERSATION-ID", "chat_1")
            .containsEntry("X-AIFABRIC-AUTH-SUBJECT-ID", "verified-user")
            .containsEntry("X-AIFABRIC-AUTH-SUBJECT-TYPE", "END_USER")
            .containsEntry("X-AIFABRIC-AUTH-MODE", "PUBLIC_RUNTIME_AUTHENTICATED")
            .containsEntry("X-AIFABRIC-AUTH-CALLER-TYPE", "PUBLIC_BROWSER")
            .containsEntry("X-AIFABRIC-AUTH-SESSION-ID", "verified-session")
            .containsEntry("X-AIFABRIC-AUTH-DEPLOYMENT-ID", "dep-123")
            .containsEntry("X-AIFABRIC-AUTH-CUSTOMER-ID", "cus-123")
            .containsEntry("X-AIFABRIC-AUTH-TENANT-ID", "ten-123")
            .containsEntry("X-AIFABRIC-AUTH-ISSUER", "commerce-app")
            .containsEntry("X-AIFABRIC-AUTH-EXPIRES-AT", "2026-04-07T01:45:00Z")
            .containsEntry("X-AIFABRIC-AUTH-SCOPES", "chat:query,chat:actions")
            .containsEntry("X-AIFABRIC-AUTH-AUDIENCES", "storefront-chat");

        assertThat(RelayTraceContextSupport.rateLimitKey(trace)).isEqualTo("verified-user");
        assertThat(RelayTraceContextSupport.effectiveSubjectId(trace)).isEqualTo("verified-user");
        assertThat(RelayTraceContextSupport.effectiveSessionId(trace)).isEqualTo("verified-session");
        assertThat(RelayTraceContextSupport.subjectType(trace)).isEqualTo("END_USER");
        assertThat(RelayTraceContextSupport.authMode(trace)).isEqualTo("PUBLIC_RUNTIME_AUTHENTICATED");
        assertThat(RelayTraceContextSupport.callerType(trace)).isEqualTo("PUBLIC_BROWSER");
        assertThat(RelayTraceContextSupport.deploymentId(trace)).isEqualTo("dep-123");
        assertThat(RelayTraceContextSupport.customerId(trace)).isEqualTo("cus-123");
        assertThat(RelayTraceContextSupport.tenantId(trace)).isEqualTo("ten-123");
        assertThat(RelayTraceContextSupport.authIssuer(trace)).isEqualTo("commerce-app");
        assertThat(RelayTraceContextSupport.grantedScopes(trace)).containsExactly("chat:query", "chat:actions");
    }

    @Test
    void missingVerifiedAuthContextNoLongerProvidesIdentityFallback() {
        TraceContextDto trace = new TraceContextDto("req_2", "chat_2", null);

        Map<String, String> headers = RelayTraceContextSupport.forwardHeaders(trace);

        assertThat(headers)
            .containsEntry("X-AIFABRIC-REQUEST-ID", "req_2")
            .containsEntry("X-AIFABRIC-CONVERSATION-ID", "chat_2");

        assertThat(RelayTraceContextSupport.rateLimitKey(trace)).isEqualTo("unknown");
        assertThat(RelayTraceContextSupport.effectiveSubjectId(trace)).isNull();
        assertThat(RelayTraceContextSupport.effectiveSessionId(trace)).isNull();
        assertThat(RelayTraceContextSupport.grantedScopes(trace)).isEmpty();
    }

    @Test
    void anonymousVerifiedAuthContextDoesNotMasqueradeAsCompatibilityUserId() {
        TraceContextDto trace = new TraceContextDto(
            "req_3",
            "chat_3",
            new VerifiedAuthContextDto(
                "anon-session",
                "ANONYMOUS_SESSION",
                "PUBLIC_RUNTIME_ANONYMOUS",
                "PUBLIC_BROWSER",
                "anon-session",
                "dep-anon",
                "cus-anon",
                "ten-anon",
                "runtime-public-bootstrap",
                "2026-04-07T02:00:00Z",
                List.of("chat:query"),
                List.of("storefront-chat")
            )
        );

        assertThat(RelayTraceContextSupport.effectiveSubjectId(trace)).isEqualTo("anon-session");
        assertThat(RelayTraceContextSupport.callerType(trace)).isEqualTo("PUBLIC_BROWSER");
        assertThat(RelayTraceContextSupport.deploymentId(trace)).isEqualTo("dep-anon");
        assertThat(RelayTraceContextSupport.customerId(trace)).isEqualTo("cus-anon");
        assertThat(RelayTraceContextSupport.tenantId(trace)).isEqualTo("ten-anon");
    }

    @Test
    void anonymousVerifiedAuthContextUsesSessionAliasWhenLegacyUserIsAbsent() {
        TraceContextDto trace = new TraceContextDto(
            "req_4",
            "chat_4",
            new VerifiedAuthContextDto(
                "anon-session-1",
                "ANONYMOUS_SESSION",
                "PUBLIC_RUNTIME_ANONYMOUS",
                "PUBLIC_BROWSER",
                null,
                "dep-anon",
                "cus-anon",
                "ten-anon",
                "runtime-public-bootstrap",
                null,
                List.of("chat:query"),
                List.of("storefront-chat")
            )
        );

        Map<String, String> headers = RelayTraceContextSupport.forwardHeaders(trace);

        assertThat(headers)
            .containsEntry("X-AIFABRIC-AUTH-SUBJECT-ID", "anon-session-1");
    }
}
