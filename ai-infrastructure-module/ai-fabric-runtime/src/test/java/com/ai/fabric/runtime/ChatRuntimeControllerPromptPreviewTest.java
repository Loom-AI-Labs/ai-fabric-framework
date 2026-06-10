package com.ai.fabric.runtime;

import com.ai.fabric.runtime.auth.RuntimeAuthCallerType;
import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthIngressMode;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimePublicTokenService;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.chat.RuntimeConversationGateway;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.ai.fabric.runtime.config.RuntimeDeploymentPromptConfigService;
import com.ai.fabric.runtime.web.ChatRuntimeController;
import com.ai.fabric.runtime.web.dto.ChatQueryRequest;
import com.ai.fabric.runtime.web.dto.ChatQueryResponse;
import com.ai.infrastructure.core.AICoreService;
import com.ai.infrastructure.dto.AIGenerationInputPart;
import com.ai.infrastructure.dto.RAGResponse;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationContextMetadataKeys;
import com.ai.infrastructure.intent.orchestration.OrchestrationResult;
import com.ai.infrastructure.intent.orchestration.OrchestrationResultType;
import com.ai.infrastructure.intent.orchestration.RAGOrchestrator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatRuntimeControllerPromptPreviewTest {

    private static final List<String> BASE_QUERY_SCOPES = List.of("chat:query");
    private static final List<String> QUERY_WITH_PROMPT_PREVIEW_SCOPES = List.of("chat:query", "chat:prompt-preview");

    @Test
    void previewRequestRequiresPromptPreviewScope() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        ChatRuntimeController controller = controllerFor(orchestrator);

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Preview this");
        request.setPromptPreview(Map.of("systemPrompt", "Use a direct tone."));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        assertThatThrownBy(() -> controller.query(request, servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN")
            .hasMessageContaining("chat:prompt-preview");
        verify(orchestrator, never()).orchestrate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.<OrchestrationContext>any()
        );
    }

    @Test
    void previewRequestPropagatesSanitizedOverlayWhenScopeGranted() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Preview this"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Preview answer")
                .build()
        );

        ChatRuntimeController controller = controllerFor(orchestrator);

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Preview this");
        request.setPromptPreview(Map.of(
            "systemPrompt", "Use a direct tone.",
            "answerGenerationPrompt", "Answer in two bullets.",
            "ignored", "should-not-pass-through"
        ));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", QUERY_WITH_PROMPT_PREVIEW_SCOPES);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getResult()).isNotNull();

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Preview this"), contextCaptor.capture());
        Object rawPromptPreview = contextCaptor.getValue().getMetadata().get("promptPreview");
        assertThat(rawPromptPreview).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, String> promptPreview = (Map<String, String>) rawPromptPreview;
        assertThat(promptPreview).containsEntry("systemPrompt", "Use a direct tone.");
        assertThat(promptPreview).containsEntry("answerGenerationPrompt", "Answer in two bullets.");
        assertThat(promptPreview).doesNotContainKey("ignored");
    }

    @Test
    void deploymentPromptConfigIsAppliedWithoutAdminAuthorization() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Preview this"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Preview answer")
                .build()
        );

        RuntimeDeploymentPromptConfigService promptConfigService = mock(RuntimeDeploymentPromptConfigService.class);
        when(promptConfigService.currentPromptOverlay()).thenReturn(Map.of(
            "systemPrompt", "Use the deployed prompt baseline.",
            "assistantUiPrompt", "Keep replies compact."
        ));
        when(promptConfigService.currentRagSimilarityThreshold()).thenReturn(0.1d);
        when(promptConfigService.currentRagMaxDocumentsUsedForContext()).thenReturn(6);
        when(promptConfigService.currentRagMaxContextChars()).thenReturn(4_500);
        when(promptConfigService.currentSmartSuggestionsEnabled()).thenReturn(Boolean.FALSE);
        when(promptConfigService.currentResponseGenerationMaxTokensConcise()).thenReturn(400);
        when(promptConfigService.currentResponseGenerationMaxTokensStandard()).thenReturn(900);
        when(promptConfigService.currentResponseGenerationMaxTokensDeep()).thenReturn(1_400);

        ChatRuntimeController controller = controllerFor(orchestrator, promptConfigService);

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Preview this");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Preview this"), contextCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, String> promptPreview = (Map<String, String>) contextCaptor.getValue().getMetadata().get("promptPreview");
        assertThat(promptPreview).containsEntry("systemPrompt", "Use the deployed prompt baseline.");
        assertThat(promptPreview).containsEntry("assistantUiPrompt", "Keep replies compact.");
        assertThat(contextCaptor.getValue().getMetadata()).containsEntry("ragSimilarityThreshold", 0.1d);
        assertThat(contextCaptor.getValue().getMetadata()).containsEntry("ragMaxDocumentsUsedForContext", 6);
        assertThat(contextCaptor.getValue().getMetadata()).containsEntry("ragMaxContextChars", 4_500);
        assertThat(contextCaptor.getValue().getMetadata()).containsEntry("smartSuggestionsEnabled", false);
        assertThat(contextCaptor.getValue().getMetadata()).containsEntry("responseGenerationMaxTokensConcise", 400);
        assertThat(contextCaptor.getValue().getMetadata()).containsEntry("responseGenerationMaxTokensStandard", 900);
        assertThat(contextCaptor.getValue().getMetadata()).containsEntry("responseGenerationMaxTokensDeep", 1_400);
    }

    @Test
    void requestPreviewOverridesDeploymentPromptConfig() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Preview this"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Preview answer")
                .build()
        );

        RuntimeDeploymentPromptConfigService promptConfigService = mock(RuntimeDeploymentPromptConfigService.class);
        when(promptConfigService.currentPromptOverlay()).thenReturn(Map.of(
            "systemPrompt", "Use the deployed prompt baseline.",
            "answerGenerationPrompt", "Answer with evidence."
        ));
        when(promptConfigService.currentSmartSuggestionsEnabled()).thenReturn(Boolean.TRUE);

        ChatRuntimeController controller = controllerFor(orchestrator, promptConfigService);

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Preview this");
        request.setPromptPreview(Map.of(
            "systemPrompt", "Use preview prompt instead.",
            "assistantUiPrompt", "Preview UI prompt."
        ));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", QUERY_WITH_PROMPT_PREVIEW_SCOPES);

        controller.query(request, servletRequest);

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Preview this"), contextCaptor.capture());
        @SuppressWarnings("unchecked")
        Map<String, String> promptPreview = (Map<String, String>) contextCaptor.getValue().getMetadata().get("promptPreview");
        assertThat(promptPreview).containsEntry("systemPrompt", "Use preview prompt instead.");
        assertThat(promptPreview).containsEntry("answerGenerationPrompt", "Answer with evidence.");
        assertThat(promptPreview).containsEntry("assistantUiPrompt", "Preview UI prompt.");
    }

    @Test
    void queryRejectsUnexpectedLegacyIdentityFields() {
        ChatRuntimeController controller = controllerFor(mock(RAGOrchestrator.class), null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Explain the failure");
        request.getUnexpectedFields().put("userId", "forged-user");
        request.getUnexpectedFields().put("sessionId", "forged-session");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        assertThatThrownBy(() -> controller.query(request, servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Unexpected request fields are not allowed on verified runtime endpoint /api/chat/me/query")
            .hasMessageContaining("userId, sessionId");
    }

    @Test
    void meQueryUsesVerifiedAuthContextWithoutLegacyBodyIdentity() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Explain the failure"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("done")
                .build()
        );

        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Explain the failure");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ResponseEntity<ChatQueryResponse> responseEntity = controller.query(request, servletRequest);
        ChatQueryResponse response = responseEntity.getBody();

        assertThat(response).isNotNull();
        assertThat(response.getSessionId()).isEqualTo("platform-session-1");
        assertThat(response.getAuthContext()).isNotNull();
        assertThat(response.getAuthContext().getSubjectId()).isEqualTo("platform-user-1");

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Explain the failure"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo("platform-user-1");
        assertThat(contextCaptor.getValue().isAuthenticated()).isTrue();
        assertThat(contextCaptor.getValue().getSessionId()).isEqualTo("platform-session-1");
        assertThat(contextCaptor.getValue().getMetadata())
            .containsEntry("subjectId", "platform-user-1")
            .containsEntry("requestedScopes", java.util.List.of("chat:query"))
            .containsEntry(OrchestrationContextMetadataKeys.QUERY_PERSISTENCE_MODE, "PERSIST_IF_AVAILABLE");
    }

    @Test
    void queryOnceUsesCanonicalResponseWithoutConversationPersistence() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Summarize this workspace"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Workspace is ready for launch.")
                .build()
        );
        RuntimeConversationGateway conversationGateway = mock(RuntimeConversationGateway.class);
        ChatRuntimeController controller = instantiateController(
            provider(orchestrator),
            conversationGateway,
            provider(null),
            provider(null),
            provider(null),
            provider(null),
            strictAuthResolver()
        );

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Summarize this workspace");
        request.setConversationId("correlation-prod-us-1");
        request.setMode("support_assistant");
        request.setPosition("productization");
        request.setContext(Map.of("pageType", "owner-product-workspace"));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ChatQueryResponse response = controller.queryOnce(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isTrue();
        assertThat(response.getAnswer()).isEqualTo("Workspace is ready for launch.");
        assertThat(response.getSafeSummary()).isEqualTo("Workspace is ready for launch.");
        assertThat(response.getConversationId()).isEqualTo("correlation-prod-us-1");
        assertThat(response.getMode()).isEqualTo("support_assistant");
        assertThat(response.getPosition()).isEqualTo("productization");
        verifyNoInteractions(conversationGateway);

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Summarize this workspace"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getMetadata())
            .containsEntry(OrchestrationContextMetadataKeys.REQUESTED_SCOPES, java.util.List.of("chat:query"))
            .containsEntry(OrchestrationContextMetadataKeys.QUERY_PERSISTENCE_MODE, "NEVER_PERSIST");
    }

    @Test
    void queryOnceExtractsTransientDocumentUrlsWithoutPersistingThemInMetadata() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Analyze this document"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Document analyzed.")
                .build()
        );
        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());
        ReflectionTestUtils.setField(controller, "transientFileUrlAllowedHosts", "*.example.com");

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Analyze this document");
        request.setConversationId("example-doc-1");
        request.setContext(Map.of(
            "pageType", "project_creation",
            "documents", List.of(Map.of(
                "documentId", "doc-1",
                "fileName", "brief.pdf",
                "contentType", "application/pdf",
                "temporaryAccessUrl", "https://files.example.com/tmp/brief.pdf?sig=abc"
            ))
        ));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        controller.queryOnce(request, servletRequest);

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Analyze this document"), contextCaptor.capture());
        OrchestrationContext context = contextCaptor.getValue();

        assertThat(context.getTransientInputParts()).hasSize(1);
        AIGenerationInputPart part = context.getTransientInputParts().get(0);
        assertThat(part.getDocumentId()).isEqualTo("doc-1");
        assertThat(part.getFileName()).isEqualTo("brief.pdf");
        assertThat(part.getContentType()).isEqualTo("application/pdf");
        assertThat(part.getUrl()).contains("https://files.example.com");

        @SuppressWarnings("unchecked")
        Map<String, Object> requestContext = (Map<String, Object>) context.getMetadata().get("requestContext");
        assertThat(requestContext.toString()).doesNotContain("files.example.com");
        assertThat(requestContext.toString()).contains("[REDACTED_TRANSIENT_FILE_URL]");
    }

    @Test
    void queryRejectsTransientDocumentUrlHostOutsideConfiguredAllowlist() {
        ChatRuntimeController controller = controllerFor(mock(RAGOrchestrator.class), null, strictAuthResolver());
        ReflectionTestUtils.setField(controller, "transientFileUrlAllowedHosts", "allowed.example.com,*.allowed.example.com");

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Analyze this document");
        request.setContext(Map.of(
            "documents", List.of(Map.of(
                "documentId", "doc-1",
                "temporaryAccessUrl", "https://files.example.com/tmp/brief.pdf?sig=abc"
            ))
        ));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        assertThatThrownBy(() -> controller.query(request, servletRequest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("temporaryAccessUrl host is not in the allowed host list");
    }

    @Test
    void queryRejectsNonHttpsTransientDocumentUrls() {
        ChatRuntimeController controller = controllerFor(mock(RAGOrchestrator.class), null, strictAuthResolver());
        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Analyze this document");
        request.setContext(Map.of(
            "documents", List.of(Map.of(
                "documentId", "doc-1",
                "temporaryAccessUrl", "http://localhost/private.pdf"
            ))
        ));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        assertThatThrownBy(() -> controller.query(request, servletRequest))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("temporaryAccessUrl must use HTTPS");
    }

    @Test
    void queryOnceRejectsUnexpectedLegacyIdentityFieldsWithQueryOncePath() {
        ChatRuntimeController controller = controllerFor(mock(RAGOrchestrator.class), null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Explain this once");
        request.getUnexpectedFields().put("userId", "forged-user");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        assertThatThrownBy(() -> controller.queryOnce(request, servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Unexpected request fields are not allowed on verified runtime endpoint /api/chat/me/query-once")
            .hasMessageContaining("userId");
    }

    @Test
    void queryOnceRequiresChatQueryScope() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("One-time answer");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", List.of("chat:suggestions"));

        assertThatThrownBy(() -> controller.queryOnce(request, servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403 FORBIDDEN")
            .hasMessageContaining("chat:query");
        verify(orchestrator, never()).orchestrate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.<OrchestrationContext>any()
        );
    }

    @Test
    void queryMapsVectorSpaceHintsFromRequestContextIntoOrchestrationMetadata() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Find the onboarding checklist"), org.mockito.ArgumentMatchers.<OrchestrationContext>any()))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Ready")
                .build());
        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Find the onboarding checklist");
        request.setContext(Map.of(
            "vectorSpace", "primary-docs",
            "entityType", "primary-docs",
            "preferredVectorSpaces", List.of("primary-docs", "reference-docs")
        ));

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        controller.queryOnce(request, servletRequest);

        ArgumentCaptor<OrchestrationContext> context = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Find the onboarding checklist"), context.capture());
        assertThat(context.getValue().getMetadata())
            .containsEntry(OrchestrationContextMetadataKeys.RAG_VECTOR_SPACE_HINT, "primary-docs")
            .containsEntry(OrchestrationContextMetadataKeys.RAG_PREFERRED_VECTOR_SPACES, List.of("primary-docs", "reference-docs"));
    }

    @Test
    void meQueryPreservesActionErrorCodeInCanonicalActionEvidence() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("I want to return my last order"), org.mockito.ArgumentMatchers.<OrchestrationContext>any()))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.ACTION_EXECUTED)
                .success(false)
                .message("Customer Account MCP requires a bound customer OAuth/PKCE access token.")
                .data(Map.of(
                    "action", "commerce_get_most_recent_order_status",
                    "actionResult", ActionResult.builder()
                        .success(false)
                        .message("Customer Account MCP requires a bound customer OAuth/PKCE access token.")
                        .errorCode("CUSTOMER_ACCOUNT_AUTH_REQUIRED")
                        .build()
                ))
                .sanitizedPayload(Map.of(
                    "safeSummary", "Customer Account MCP requires a bound customer OAuth/PKCE access token.",
                    "data", Map.of(
                        "action", "commerce_get_most_recent_order_status",
                        "actionResult", Map.of(
                            "success", false,
                            "message", "Customer Account MCP requires a bound customer OAuth/PKCE access token."
                        )
                    )
                ))
                .build());

        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());
        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("I want to return my last order");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.isSuccess()).isFalse();
        assertThat(response.getFallbackReason()).isEqualTo("CUSTOMER_ACCOUNT_AUTH_REQUIRED");
        assertThat(response.getActions()).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> action = (Map<String, Object>) response.getActions().get(0);
        assertThat(action).containsEntry("action", "commerce_get_most_recent_order_status");
        assertThat(action).containsEntry("errorCode", "CUSTOMER_ACCOUNT_AUTH_REQUIRED");

        @SuppressWarnings("unchecked")
        Map<String, Object> actionResult = (Map<String, Object>) action.get("actionResult");
        assertThat(actionResult).containsEntry("errorCode", "CUSTOMER_ACCOUNT_AUTH_REQUIRED");
    }

    @Test
    void meQueryExposesSafeResultMetadataInCanonicalResponse() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        Map<String, Object> readActionResolution = Map.of(
            "attempted", true,
            "executedActionsCount", 1,
            "executedActions", List.of(Map.of("action", "check_availability"))
        );
        when(orchestrator.orchestrate(eq("Check live availability for SKU-0001"), org.mockito.ArgumentMatchers.<OrchestrationContext>any()))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("SKU-0001 is available.")
                .metadata(Map.of("readActionResolution", readActionResolution))
                .data(Map.of("readActionResolution", readActionResolution))
                .build());

        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());
        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Check live availability for SKU-0001");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getMetadata()).containsKey("readActionResolution");
        assertThat(response.getMetadata().get("readActionResolution")).isEqualTo(readActionResolution);
    }

    @Test
    void meQueryExposesRawResultDocumentsWhenSanitizedPayloadOmitsSources() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        List<Map<String, Object>> documents = List.of(Map.of(
            "id", "policy-shared-refund",
            "source", "shared-marketplace-refund-policy",
            "metadata", Map.of("adapterType", "shared-index")
        ));
        when(orchestrator.orchestrate(eq("What is the refund policy?"), org.mockito.ArgumentMatchers.<OrchestrationContext>any()))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Refunds are available within 30 days.")
                .data(Map.of(
                    "answer", "Refunds are available within 30 days.",
                    "documents", documents
                ))
                .sanitizedPayload(Map.of(
                    "safeSummary", "Refunds are available within 30 days.",
                    "data", Map.of("answer", "Refunds are available within 30 days.")
                ))
                .build());

        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());
        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("What is the refund policy?");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getSources()).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) response.getSources().getFirst();
        assertThat(source)
            .containsEntry("id", "policy-shared-refund")
            .containsEntry("source", "shared-marketplace-refund-policy");
    }

    @Test
    void meQueryExposesCanonicalRagResponseDocuments() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        RAGResponse ragResponse = RAGResponse.builder()
            .documents(List.of(RAGResponse.RAGDocument.builder()
                .id("product-ironpeak")
                .title("IronPeak Workstation 17 Laptop")
                .content("High performance laptop evidence")
                .type("product")
                .score(0.92d)
                .metadata(Map.of("vectorSpace", "product"))
                .build()))
            .context("High performance laptop evidence")
            .success(true)
            .build();
        when(orchestrator.orchestrate(eq("summarize high performance laptops for gaming"), org.mockito.ArgumentMatchers.<OrchestrationContext>any()))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("IronPeak is available.")
                .data(Map.of(
                    "answer", "IronPeak is available.",
                    "documents", ragResponse.getDocuments(),
                    "ragResponse", ragResponse
                ))
                .sanitizedPayload(Map.of("safeSummary", "IronPeak is available."))
                .build());

        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());
        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("summarize high performance laptops for gaming");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getSources()).hasSize(1);
        assertThat(response.getRagResponse()).containsKey("documents");
    }

    @Test
    void meQueryExposesReadActionEvidenceAsSourcesWhenNoRagDocumentsExist() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        Map<String, Object> readActionResolution = Map.of(
            "attempted", true,
            "executedActions", List.of(Map.of(
                "action", "catalog_search",
                "category", "catalog",
                "groundingUsable", true,
                "evidenceSummary", "{\"items\":[{\"title\":\"Titan Gaming 16 Laptop\",\"price\":\"2199.00\"}]}"
            ))
        );
        when(orchestrator.orchestrate(eq("Find gaming laptops"), org.mockito.ArgumentMatchers.<OrchestrationContext>any()))
            .thenReturn(OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("Titan Gaming 16 Laptop is listed.")
                .data(Map.of(
                    "answer", "Titan Gaming 16 Laptop is listed.",
                    "documents", List.of(),
                    "readActionResolution", readActionResolution
                ))
                .metadata(Map.of("readActionResolution", readActionResolution))
                .sanitizedPayload(Map.of("safeSummary", "Titan Gaming 16 Laptop is listed."))
                .build());

        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());
        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Find gaming laptops");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getSources()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> source = (Map<String, Object>) response.getSources().getFirst();
        assertThat(source)
            .containsEntry("title", "catalog_search")
            .containsEntry("type", "read-action-evidence");
        assertThat(source.get("content")).asString().contains("Titan Gaming 16 Laptop");
    }

    @Test
    void meQueryRequiresVerifiedAuthContext() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Explain the failure");

        assertThatThrownBy(() -> controller.query(request, new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("401 UNAUTHORIZED")
            .hasMessageContaining("Verified runtime auth context is required");
    }

    @Test
    void strictModeRejectsLegacyRequestIdentityWithoutVerifiedHeaders() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        ChatRuntimeController controller = controllerFor(orchestrator, null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Hello");
        request.getUnexpectedFields().put("userId", "legacy-user");

        assertThatThrownBy(() -> controller.query(request, new MockHttpServletRequest()))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Unexpected request fields are not allowed on verified runtime endpoint /api/chat/me/query")
            .hasMessageContaining("userId");
    }

    @Test
    void verifiedQueryStillRejectsLegacyIdentityFields() {
        ChatRuntimeController controller = controllerFor(mock(RAGOrchestrator.class), null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Hello");
        request.getUnexpectedFields().put("userId", "legacy-user");
        request.getUnexpectedFields().put("sessionId", "legacy-session");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        assertThatThrownBy(() -> controller.query(request, servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Unexpected request fields are not allowed on verified runtime endpoint /api/chat/me/query")
            .hasMessageContaining("userId, sessionId");
    }

    @Test
    void verifiedQueryRejectsLegacyIdentityFieldsEvenWhenValuesMatchAuthContext() {
        ChatRuntimeController controller = controllerFor(mock(RAGOrchestrator.class), null, strictAuthResolver());

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Hello");
        request.getUnexpectedFields().put("userId", "platform-user-1");
        request.getUnexpectedFields().put("sessionId", "platform-session-1");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        addVerifiedAuthHeaders(servletRequest, "platform-user-1", "platform-session-1", BASE_QUERY_SCOPES);

        assertThatThrownBy(() -> controller.query(request, servletRequest))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400 BAD_REQUEST")
            .hasMessageContaining("Unexpected request fields are not allowed on verified runtime endpoint /api/chat/me/query")
            .hasMessageContaining("userId, sessionId");
    }

    @Test
    void meQueryUsesPublicAnonymousBearerTokenWithoutRequestIdentity() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Anonymous question"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("done")
                .build()
        );

        RuntimeAuthProperties properties = new RuntimeAuthProperties();
        properties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        properties.getPublicTokens().setSigningKey("public-secret");
        properties.getPublicTokens().setIssuer("runtime-public-test");
        properties.getPublicTokens().getBootstrap().setEnabled(true);
        RuntimePublicTokenService tokenService = new RuntimePublicTokenService(properties);
        RuntimeRequestAuthResolver authResolver = new RuntimeRequestAuthResolver(properties, tokenService);
        String token = tokenService.issueAnonymousToken().token();

        ChatRuntimeController controller = controllerFor(orchestrator, null, authResolver);

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Anonymous question");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer " + token);

        ResponseEntity<ChatQueryResponse> responseEntity = controller.query(request, servletRequest);
        ChatQueryResponse response = responseEntity.getBody();
        String issuedSessionId = response == null ? null : response.getSessionId();

        assertThat(response).isNotNull();
        assertThat(issuedSessionId).startsWith("anon-");
        assertThat(response.getAuthContext()).isNotNull();
        assertThat(response.getAuthContext().getSubjectId()).isEqualTo(issuedSessionId);
        assertThat(response.getAuthContext().getSubjectType()).isEqualTo(RuntimeAuthSubjectType.ANONYMOUS_SESSION.name());
        assertThat(response.getAuthContext().getAuthMode()).isEqualTo(RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS.name());
        assertThat(response.getAuthContext().getSessionId()).isEqualTo(issuedSessionId);
        assertThat(responseEntity.getHeaders().getFirst("X-AIFABRIC-RUNTIME-AUTH-MODE"))
            .isEqualTo(RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS.name());

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Anonymous question"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getUserId()).isNull();
        assertThat(contextCaptor.getValue().isAuthenticated()).isFalse();
        assertThat(contextCaptor.getValue().getSessionId()).isEqualTo(issuedSessionId);
        assertThat(contextCaptor.getValue().getMetadata())
            .containsEntry("authMode", RuntimeAuthMode.PUBLIC_RUNTIME_ANONYMOUS.name())
            .containsEntry("subjectType", RuntimeAuthSubjectType.ANONYMOUS_SESSION.name())
            .containsEntry("authIssuer", "runtime-public-test")
            .containsEntry("requestedScopes", java.util.List.of("chat:query"));
    }

    @Test
    void meQueryUsesPublicAuthenticatedBearerTokenWithoutLegacyRequestIdentity() {
        RAGOrchestrator orchestrator = mock(RAGOrchestrator.class);
        when(orchestrator.orchestrate(eq("Authenticated question"), org.mockito.ArgumentMatchers.<OrchestrationContext>any())).thenReturn(
            OrchestrationResult.builder()
                .type(OrchestrationResultType.INFORMATION_PROVIDED)
                .success(true)
                .message("done")
                .build()
        );

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
            List.of("chat:query", "chat:conversations"),
            "commerce-app",
            List.of("storefront-chat")
        ).token();

        ChatRuntimeController controller = controllerFor(orchestrator, null, authResolver);

        ChatQueryRequest request = new ChatQueryRequest();
        request.setQuery("Authenticated question");

        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.addHeader("Authorization", "Bearer " + token);

        ChatQueryResponse response = controller.query(request, servletRequest).getBody();

        assertThat(response).isNotNull();
        assertThat(response.getSessionId()).isEqualTo("session-public-authenticated");
        assertThat(response.getAuthContext()).isNotNull();
        assertThat(response.getAuthContext().getSubjectId()).isEqualTo("customer-123");
        assertThat(response.getAuthContext().getSubjectType()).isEqualTo(RuntimeAuthSubjectType.END_USER.name());
        assertThat(response.getAuthContext().getAuthMode()).isEqualTo(RuntimeAuthMode.PUBLIC_RUNTIME_AUTHENTICATED.name());
        assertThat(response.getAuthContext().getSessionId()).isEqualTo("session-public-authenticated");

        ArgumentCaptor<OrchestrationContext> contextCaptor = ArgumentCaptor.forClass(OrchestrationContext.class);
        verify(orchestrator).orchestrate(eq("Authenticated question"), contextCaptor.capture());
        assertThat(contextCaptor.getValue().getUserId()).isEqualTo("customer-123");
        assertThat(contextCaptor.getValue().isAuthenticated()).isTrue();
        assertThat(contextCaptor.getValue().getSessionId()).isEqualTo("session-public-authenticated");
        assertThat(contextCaptor.getValue().getMetadata())
            .containsEntry("subjectId", "customer-123")
            .containsEntry("authMode", RuntimeAuthMode.PUBLIC_RUNTIME_AUTHENTICATED.name())
            .containsEntry("subjectType", RuntimeAuthSubjectType.END_USER.name())
            .containsEntry("authIssuer", "commerce-app")
            .containsEntry("requestedScopes", java.util.List.of("chat:query"));
    }

    private ChatRuntimeController controllerFor(RAGOrchestrator orchestrator) {
        return controllerFor(orchestrator, null, defaultAuthResolver());
    }

    private ChatRuntimeController controllerFor(RAGOrchestrator orchestrator,
                                                RuntimeDeploymentPromptConfigService promptConfigService) {
        return controllerFor(orchestrator, promptConfigService, defaultAuthResolver());
    }

    private ChatRuntimeController controllerFor(RAGOrchestrator orchestrator,
                                                RuntimeDeploymentPromptConfigService promptConfigService,
                                                RuntimeRequestAuthResolver authResolver) {
        return instantiateController(
            provider(orchestrator),
            mock(RuntimeConversationGateway.class),
            provider(null),
            provider(null),
            provider(promptConfigService),
            provider(null),
            authResolver
        );
    }

    private ChatRuntimeController instantiateController(ObjectProvider<?> orchestratorProvider,
                                                        RuntimeConversationGateway conversationGateway,
                                                        ObjectProvider<?> aiCoreServiceProvider,
                                                        ObjectProvider<?> aiActionRegistryProvider,
                                                        ObjectProvider<?> promptConfigProvider,
                                                        ObjectProvider<?> shellConfigProvider,
                                                        RuntimeRequestAuthResolver authResolver) {
        try {
            Constructor<?> constructor = ChatRuntimeController.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return (ChatRuntimeController) constructor.newInstance(
                orchestratorProvider,
                conversationGateway,
                aiCoreServiceProvider,
                aiActionRegistryProvider,
                promptConfigProvider,
                shellConfigProvider,
                authResolver
            );
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private RuntimeRequestAuthResolver defaultAuthResolver() {
        RuntimeAuthProperties properties = authProperties();
        return new RuntimeRequestAuthResolver(properties);
    }

    private RuntimeRequestAuthResolver strictAuthResolver() {
        RuntimeAuthProperties properties = authProperties();
        return new RuntimeRequestAuthResolver(properties);
    }

    private void addVerifiedAuthHeaders(MockHttpServletRequest request,
                                        String subjectId,
                                        String sessionId,
                                        List<String> scopes) {
        RuntimePrivateAssertionTestSupport.addPrivateRuntimeHeaders(
            request,
            authProperties(),
            RuntimeAuthContext.builder()
                .subjectId(subjectId)
                .subjectType(RuntimeAuthSubjectType.INTERNAL_PLATFORM_USER)
                .authMode(RuntimeAuthMode.PLATFORM_PROXY_SESSION)
                .callerType(RuntimeAuthCallerType.PLATFORM_PROXY)
                .sessionId(sessionId)
                .deploymentId("dep-123")
                .issuer("platform-backend")
                .audiences(List.of("dep-123"))
                .grantedScopes(scopes)
                .expiresAt(Instant.now().plusSeconds(300))
                .build()
        );
    }

    private RuntimeAuthProperties authProperties() {
        RuntimeAuthProperties properties = RuntimePrivateAssertionTestSupport.strictPrivateRuntimeProperties();
        properties.getIngress().setAcceptedIssuers(List.of("platform-backend"));
        properties.getIngress().setAcceptedAudiences(List.of("dep-123"));
        return properties;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ObjectProvider provider(Object value) {
        ObjectProvider provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(value);
        return provider;
    }
}
