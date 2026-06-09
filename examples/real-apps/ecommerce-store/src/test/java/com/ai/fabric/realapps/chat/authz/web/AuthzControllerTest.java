package com.ai.fabric.realapps.chat.authz.web;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AuthzControllerTest {

    private final AuthzController controller = new AuthzController();

    @Test
    void allowsAnonymousRootChatAccess() {
        ResponseEntity<AuthzController.AuthzCheckResponse> response = controller.check(
            new AuthzController.AuthzCheckRequest(
                "AUTH_CONTEXT_V1",
                "req-1",
                null,
                "anon-session-1",
                "ANONYMOUS_SESSION",
                "PUBLIC_RUNTIME_ANONYMOUS",
                "anon-session-1",
                List.of("chat:query"),
                List.of("chat:query"),
                "rag:intent",
                "READ",
                Map.of("entryPoint", "RAG_ORCHESTRATOR"),
                Map.of("authenticated", false),
                Map.of(),
                new AuthzController.VerifiedAuthContext(
                    "anon-session-1",
                    "ANONYMOUS_SESSION",
                    "PUBLIC_RUNTIME_ANONYMOUS",
                    "PUBLIC_BROWSER",
                    "anon-session-1",
                    "dep-1",
                    null,
                    null,
                    "ecommerce-demo",
                    List.of("chat:query")
                )
            )
        );

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().granted()).isTrue();
        assertThat(response.getBody().reason()).isEqualTo("anonymous-chat-read");
    }

    @Test
    void allowsAnonymousSafeCartAction() {
        ResponseEntity<AuthzController.AuthzCheckResponse> response = controller.check(
            new AuthzController.AuthzCheckRequest(
                "AUTH_CONTEXT_V1",
                "req-2",
                null,
                "anon-session-1",
                "ANONYMOUS_SESSION",
                "PUBLIC_RUNTIME_ANONYMOUS",
                "anon-session-1",
                List.of("chat:query"),
                List.of(),
                "action:add_to_cart",
                "EXECUTE_ACTION",
                Map.of("actionId", "add_to_cart"),
                Map.of("actionId", "add_to_cart"),
                Map.of("sku", "SKU-1"),
                new AuthzController.VerifiedAuthContext(
                    "anon-session-1",
                    "ANONYMOUS_SESSION",
                    "PUBLIC_RUNTIME_ANONYMOUS",
                    "PUBLIC_BROWSER",
                    "anon-session-1",
                    "dep-1",
                    null,
                    null,
                    "ecommerce-demo",
                    List.of("chat:query")
                )
            )
        );

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().granted()).isTrue();
        assertThat(response.getBody().reason()).isEqualTo("anonymous-action-allowed");
    }

    @Test
    void deniesAnonymousSensitiveAction() {
        ResponseEntity<AuthzController.AuthzCheckResponse> response = controller.check(
            new AuthzController.AuthzCheckRequest(
                "AUTH_CONTEXT_V1",
                "req-3",
                null,
                "anon-session-1",
                "ANONYMOUS_SESSION",
                "PUBLIC_RUNTIME_ANONYMOUS",
                "anon-session-1",
                List.of("chat:query"),
                List.of(),
                "action:list_orders",
                "EXECUTE_ACTION",
                Map.of("actionId", "list_orders"),
                Map.of("actionId", "list_orders"),
                Map.of(),
                new AuthzController.VerifiedAuthContext(
                    "anon-session-1",
                    "ANONYMOUS_SESSION",
                    "PUBLIC_RUNTIME_ANONYMOUS",
                    "PUBLIC_BROWSER",
                    "anon-session-1",
                    "dep-1",
                    null,
                    null,
                    "ecommerce-demo",
                    List.of("chat:query")
                )
            )
        );

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().granted()).isFalse();
        assertThat(response.getBody().reason()).isEqualTo("ANONYMOUS_POLICY_DENY");
    }

    @Test
    void allowsAuthenticatedCallerWithoutLegacyUserId() {
        ResponseEntity<AuthzController.AuthzCheckResponse> response = controller.check(
            new AuthzController.AuthzCheckRequest(
                "AUTH_CONTEXT_V1",
                "req-4",
                null,
                "usr-123",
                "END_USER",
                "PUBLIC_RUNTIME_AUTHENTICATED",
                "sess-1",
                List.of("chat:query"),
                List.of(),
                "rag:intent",
                "READ",
                Map.of(),
                Map.of("authenticated", true),
                Map.of(),
                new AuthzController.VerifiedAuthContext(
                    "usr-123",
                    "END_USER",
                    "PUBLIC_RUNTIME_AUTHENTICATED",
                    "PUBLIC_BROWSER",
                    "sess-1",
                    "dep-1",
                    "cust-1",
                    "ten-1",
                    "ecommerce-demo",
                    List.of("chat:query")
                )
            )
        );

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().granted()).isTrue();
        assertThat(response.getBody().reason()).isEqualTo("ok");
    }
}
