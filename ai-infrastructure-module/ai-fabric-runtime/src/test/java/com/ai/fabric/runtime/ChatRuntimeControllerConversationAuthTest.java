package com.ai.fabric.runtime;

import com.ai.fabric.runtime.auth.RuntimeAuthCallerType;
import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthIngressMode;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimePublicTokenService;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.chat.RuntimeConversationGateway;
import com.ai.fabric.runtime.chat.RuntimeConversationRecord;
import com.ai.fabric.runtime.chat.RuntimeConversationTurnRecord;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.ai.fabric.runtime.web.ChatRuntimeController;
import com.ai.fabric.runtime.web.dto.ConversationResponse;
import com.ai.fabric.runtime.web.dto.ConversationSummaryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatRuntimeControllerConversationAuthTest {

    @Test
    void conversationDetailRequiresVerifiedRuntimeIdentity() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );
        assertThatThrownBy(() -> controller.getConversation("chat-1", new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Verified runtime auth context is required");
    }

    @Test
    void conversationListRequiresVerifiedRuntimeIdentity() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );
        assertThatThrownBy(() -> controller.listConversations(new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Verified runtime auth context is required");
    }

    @Test
    void conversationDeleteRequiresVerifiedRuntimeIdentity() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );
        assertThatThrownBy(() -> controller.deleteConversation("chat-1", new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Verified runtime auth context is required");
    }

    @Test
    void listConversationsUsesVerifiedAuthContextOwnerWithoutLegacyQuerySurface() {
        RuntimeConversationGateway conversationGateway = mock(RuntimeConversationGateway.class);
        when(conversationGateway.isAvailable()).thenReturn(true);
        when(conversationGateway.listConversations("verified-user"))
            .thenReturn(List.of(session("chat-1", "verified-user")));

        ChatRuntimeController controller = instantiateController(
            conversationGateway,
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");

        ResponseEntity<List<ConversationSummaryResponse>> responseEntity = controller
            .listConversations(servletRequest);
        List<ConversationSummaryResponse> response = responseEntity.getBody();

        assertThat(response).hasSize(1);
        assertThat(response.getFirst().getAuthContext()).isNotNull();
        assertThat(response.getFirst().getAuthContext().getSubjectId()).isEqualTo("verified-user");
        verify(conversationGateway).listConversations("verified-user");
    }

    @Test
    void listConversationsRejectsLegacyQueryParamsOnVerifiedRoute() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");
        servletRequest.setParameter("ownerId", "legacy-owner");

        assertThatThrownBy(() -> controller.listConversations(servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Legacy request identity fields are not allowed on verified runtime endpoint /api/chat/me/conversations")
            .hasMessageContaining("ownerId");
    }

    @Test
    void getConversationUsesVerifiedAuthContextOwnerWithoutLegacyQuerySurface() {
        RuntimeConversationGateway conversationGateway = mock(RuntimeConversationGateway.class);
        when(conversationGateway.isAvailable()).thenReturn(true);
        when(conversationGateway.getConversation("chat-1", "verified-user"))
            .thenReturn(session("chat-1", "verified-user"));

        ChatRuntimeController controller = instantiateController(
            conversationGateway,
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");

        ResponseEntity<ConversationResponse> responseEntity = controller
            .getConversation("chat-1", servletRequest);
        ConversationResponse response = responseEntity.getBody();

        assertThat(response).isNotNull();
        assertThat(response.getAuthContext()).isNotNull();
        assertThat(response.getAuthContext().getSubjectId()).isEqualTo("verified-user");
        verify(conversationGateway).getConversation("chat-1", "verified-user");
    }

    @Test
    void getConversationRejectsLegacyQueryParamsOnVerifiedRoute() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");
        servletRequest.setParameter("userId", "legacy-user");
        servletRequest.setParameter("sessionId", "legacy-session");

        assertThatThrownBy(() -> controller.getConversation("chat-1", servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Legacy request identity fields are not allowed on verified runtime endpoint /api/chat/me/conversations/{conversationId}")
            .hasMessageContaining("userId, sessionId");
    }

    @Test
    void deleteConversationUsesVerifiedAuthContextOwnerWithoutLegacyQuerySurface() {
        RuntimeConversationGateway conversationGateway = mock(RuntimeConversationGateway.class);
        ChatRuntimeController controller = instantiateController(
            conversationGateway,
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");

        ResponseEntity<Void> responseEntity = controller.deleteConversation("chat-1", servletRequest);
        assertThat(responseEntity.getStatusCode().value()).isEqualTo(204);

        verify(conversationGateway).deleteConversation("chat-1", "verified-user");
    }

    @Test
    void authAwareConversationEndpointsRequireVerifiedAuthContext() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );

        assertThatThrownBy(() -> controller.listConversations(new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Verified runtime auth context is required");
    }

    @Test
    void strictConversationModeRejectsLegacyOwnerOnlyQueryParams() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");
        servletRequest.setParameter("ownerId", "legacy-owner");

        assertThatThrownBy(() -> controller.listConversations(servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Legacy request identity fields are not allowed on verified runtime endpoint /api/chat/me/conversations")
            .hasMessageContaining("ownerId");
    }

    @Test
    void strictConversationModeRejectsLegacyConversationDetailQueryParams() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");
        servletRequest.setParameter("userId", "legacy-user");
        servletRequest.setParameter("ownerId", "legacy-owner");

        assertThatThrownBy(() -> controller.getConversation("chat-1", servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Legacy request identity fields are not allowed on verified runtime endpoint /api/chat/me/conversations/{conversationId}")
            .hasMessageContaining("userId, ownerId");
    }

    @Test
    void verifiedConversationEndpointsRejectLegacyOwnerQueryWhenVerifiedIdentityExists() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");

        servletRequest.setParameter("userId", "legacy-user");
        servletRequest.setParameter("ownerId", "legacy-owner");

        assertThatThrownBy(() -> controller.getConversation("chat-1", servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Legacy request identity fields are not allowed on verified runtime endpoint /api/chat/me/conversations/{conversationId}")
            .hasMessageContaining("userId, ownerId");
    }

    @Test
    void verifiedConversationEndpointsRejectLegacyOwnerAliasesEvenWhenValuesMatch() {
        ChatRuntimeController controller = instantiateController(
            mock(RuntimeConversationGateway.class),
            strictAuthResolver()
        );

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "verified-user", "verified-session");

        servletRequest.setParameter("userId", "verified-user");
        servletRequest.setParameter("ownerId", "verified-user");

        assertThatThrownBy(() -> controller.getConversation("chat-1", servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Legacy request identity fields are not allowed on verified runtime endpoint /api/chat/me/conversations/{conversationId}")
            .hasMessageContaining("userId, ownerId");
    }

    @Test
    void listConversationsRejectsPublicAuthenticatedTokenWithoutConversationScope() {
        RuntimeConversationGateway conversationGateway = mock(RuntimeConversationGateway.class);
        when(conversationGateway.isAvailable()).thenReturn(true);

        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        properties.getPublicTokens().setSigningKey("public-secret");
        properties.getPublicTokens().setIssuer("runtime-public-test");
        properties.getPublicTokens().setAcceptedIssuers(List.of("runtime-public-test", "commerce-app"));
        properties.getPublicTokens().setAcceptedAudiences(List.of("storefront-chat"));
        properties.getPublicTokens().setDefaultAudience("storefront-chat");
        RuntimePublicTokenService tokenService = new RuntimePublicTokenService(properties);
        RuntimeRequestAuthResolver authResolver = new RuntimeRequestAuthResolver(properties, tokenService);
        String token = tokenService.issueAuthenticatedToken(
            "customer-123",
            RuntimeAuthSubjectType.END_USER,
            "session-public-authenticated",
            "dep-public",
            "cus-public",
            "ten-public",
            List.of("chat:query"),
            "commerce-app",
            List.of("storefront-chat")
        ).token();

        ChatRuntimeController controller = instantiateController(conversationGateway, authResolver);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer " + token);

        assertThatThrownBy(() -> controller.listConversations(servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN")
            .hasMessageContaining("chat:conversations");
    }

    private RuntimeRequestAuthResolver strictAuthResolver() {
        RuntimeAuthProperties properties = authProperties();
        return new RuntimeRequestAuthResolver(properties);
    }

    private void addVerifiedAuthHeaders(MockHttpServletRequest request, String subjectId, String sessionId) {
        RuntimePrivateAssertionTestSupport.addPrivateRuntimeHeaders(
            request,
            authProperties(),
            RuntimeAuthContext.builder()
                .subjectId(subjectId)
                .subjectType(RuntimeAuthSubjectType.END_USER)
                .authMode(RuntimeAuthMode.PRIVATE_RUNTIME_BACKEND_MEDIATED)
                .callerType(RuntimeAuthCallerType.TRUSTED_BACKEND)
                .sessionId(sessionId)
                .deploymentId("dep-123")
                .issuer("backend-test")
                .audiences(List.of("dep-123"))
                .grantedScopes(List.of("chat:conversations"))
                .expiresAt(Instant.now().plusSeconds(300))
                .build()
        );
    }

    private RuntimeAuthProperties authProperties() {
        RuntimeAuthProperties properties = RuntimePrivateAssertionTestSupport.strictPrivateRuntimeProperties();
        properties.getIngress().setAcceptedIssuers(List.of("backend-test"));
        properties.getIngress().setAcceptedAudiences(List.of("dep-123"));
        return properties;
    }

    private RuntimeConversationRecord session(String id, String ownerId) {
        LocalDateTime now = LocalDateTime.of(2026, 4, 6, 12, 0);
        return new RuntimeConversationRecord(
            id,
            ownerId,
            "ACTIVE",
            now,
            now,
            List.of(new RuntimeConversationTurnRecord(now, "hello", "world"))
        );
    }

    private ChatRuntimeController instantiateController(RuntimeConversationGateway conversationGateway,
                                                        RuntimeRequestAuthResolver authResolver) {
        try {
            Constructor<?> constructor = ChatRuntimeController.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return (ChatRuntimeController) constructor.newInstance(
                provider(null),
                conversationGateway,
                provider(null),
                provider(null),
                provider(null),
                provider(null),
                authResolver
            );
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ObjectProvider provider(Object value) {
        ObjectProvider provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
