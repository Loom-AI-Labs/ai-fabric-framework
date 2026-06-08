package com.ai.infrastructure.connector.rest.util;

import com.ai.infrastructure.connector.rest.api.TraceContextDto;
import com.ai.infrastructure.connector.rest.api.VerifiedAuthContextDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceContextSupportTest {

    @Test
    void forwardsVerifiedAuthHeadersOnly() {
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

        Map<String, String> headers = TraceContextSupport.forwardHeaders(trace);

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
    }

    @Test
    void usesAnonymousVerifiedSubjectAsSessionAliasWhenNoLegacySessionExists() {
        TraceContextDto trace = new TraceContextDto(
            "req_2",
            "chat_2",
            new VerifiedAuthContextDto(
                "anon-session-1",
                "ANONYMOUS_SESSION",
                "PUBLIC_RUNTIME_ANONYMOUS",
                "PUBLIC_BROWSER",
                null,
                "dep-456",
                "cus-456",
                "ten-456",
                "runtime-bootstrap",
                null,
                List.of("chat:query"),
                List.of("storefront-chat")
            )
        );

        Map<String, String> headers = TraceContextSupport.forwardHeaders(trace);
        Map<String, Object> templateMap = TraceContextSupport.templateMap(trace);

        assertThat(headers)
            .containsEntry("X-AIFABRIC-AUTH-SUBJECT-ID", "anon-session-1");
        assertThat(templateMap)
            .doesNotContainKey("sessionId")
            .doesNotContainKey("userId");
        assertThat(((Map<?, ?>) templateMap.get("authContext")).get("subjectId")).isEqualTo("anon-session-1");
        assertThat(((Map<?, ?>) templateMap.get("authContext")).get("sessionId")).isEqualTo("anon-session-1");
        assertThat(((Map<?, ?>) templateMap.get("authContext")).get("audiences")).isEqualTo(List.of("storefront-chat"));
    }
}
