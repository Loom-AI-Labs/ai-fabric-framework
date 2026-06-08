package com.ai.fabric.runtime;

import com.ai.fabric.runtime.auth.RuntimeAuthIngressMode;
import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimeAuthCallerType;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.auth.RuntimePrivateAssertionService;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.admin.RuntimeActionCatalogGateway;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.ai.fabric.runtime.config.RuntimeDeploymentKnowledgeSourceConfigService;
import com.ai.fabric.runtime.config.RuntimeDeploymentShellConfigService;
import com.ai.fabric.runtime.web.admin.RuntimeAdminOverviewController;
import com.ai.fabric.runtime.web.admin.RuntimeAdminScopeCatalog;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.dto.AISearchRequest;
import com.ai.infrastructure.dto.AISearchResponse;
import com.ai.infrastructure.dto.VectorRecord;
import com.ai.infrastructure.dto.VectorScanPage;
import com.ai.infrastructure.dto.VectorScanRequest;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIContributionProvenance;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResultPresentationHint;
import com.ai.infrastructure.intent.action.ActionSideEffectLevel;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.connector.ConnectorActionWebhookPolicyCatalog;
import com.ai.infrastructure.intent.action.connector.ConnectorWebhookTargetDefinition;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecision;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorRule;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorTrigger;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.rag.source.SearchSourceRegistry;
import com.ai.infrastructure.shell.BuiltInShellCatalog;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuntimeAdminOverviewControllerTest {

    @Test
    void overviewIncludesVectorScopeDiagnostics() {
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        AIEntityConfigurationLoader entityConfigurationLoader = mock(AIEntityConfigurationLoader.class);
        VectorDatabaseService vectorDatabaseService = new TestVectorDatabaseService();
        HttpServletRequest request = mock(HttpServletRequest.class);
        RuntimeAuthProperties authProperties = new RuntimeAuthProperties();
        authProperties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        authProperties.getIngress().getTrustedBackend().setApiKeyValue("runtime-secret");
        authProperties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-secret");
        authProperties.getIngress().setAcceptedIssuers(List.of("platform-poc:SESSION"));
        authProperties.getIngress().setAcceptedAudiences(List.of("dep-auth"));
        authProperties.getPublicTokens().setSigningKey("public-signing-secret");
        authProperties.getPublicTokens().setIssuer("runtime-public-bootstrap");
        authProperties.getPublicTokens().getAcceptedIssuers().add("commerce-app");
        authProperties.getPublicTokens().getAcceptedAudiences().add("storefront-chat");
        authProperties.getPublicTokens().setDefaultAudience("storefront-chat");
        authProperties.getPublicTokens().getBootstrap().setEnabled(true);
        authProperties.getPublicTokens().getBootstrap().getAllowedOrigins().add("https://storefront.example");

        when(actionRegistry.getAllMetadata()).thenReturn(java.util.List.of(
            AIActionMetaData.builder()
                .name("create_purchase_order")
                .displayName("Create Purchase Order")
                .category("commerce")
                .description("Create a new purchase order")
                .accessMode(ActionAccessMode.WRITE_ONLY)
                .confirmationRequired(true)
                .groundingEligible(false)
                .sideEffectLevel(ActionSideEffectLevel.MUTATING)
                .resultPresentationHint(ActionResultPresentationHint.STATUS)
                .builtInModuleId("purchase-orders")
                .builtInCardId("purchase-order-status")
                .provenance(AIContributionProvenance.builder()
                    .contributionType("ACTION")
                    .sourceType("ACTION_CATALOG")
                    .sourceId("create_purchase_order")
                    .sourceLocation("classpath:test-actions.yml")
                    .build())
                .build()
        ));
        when(entityConfigurationLoader.getSupportedEntityTypes()).thenReturn(Set.of("product", "policy", "review"));
        Map<String, Object> vectorScope = new LinkedHashMap<>();
        vectorScope.put("sharedStorage", true);
        vectorScope.put("scopeType", "NAMESPACE_PREFIX");
        vectorScope.put("rootResourceValue", "shared-index");
        vectorScope.put("scopePrefix", "customer-a--tenant-b");
        vectorScope.put("scopePattern", "customer-a--tenant-b__<entity-type>");
        ((TestVectorDatabaseService) vectorDatabaseService).diagnostics = vectorScope;

        RuntimeAdminOverviewController controller = instantiateController(
            actionRegistry,
            mock(RuntimeActionCatalogGateway.class),
            entityConfigurationLoader,
            vectorDatabaseService,
            authProperties,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null),
            confirmationProvider(),
            webhookPolicyProvider(),
            knowledgeSourceConfigProvider(),
            shellConfigProvider(),
            searchSourceRegistryProvider()
        );
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "entityConfigLocation", "https://platform.example/entities");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "promptConfigLocation", "https://platform.example/prompts");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "knowledgeSourceConfigLocation", "https://platform.example/knowledge-sources");
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "shellConfigLocation", "https://platform.example/shell");

        ResponseEntity<?> response = controller.overview(authorizedRequest(authProperties, RuntimeAdminScopeCatalog.RUNTIME_ADMIN_OVERVIEW));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("supportsVectorScan", true);
        assertThat(body).containsEntry("actionsCount", 1L);
        assertThat(body).containsEntry("groundingEligibleActionsCount", 0L);
        assertThat(body).containsEntry("readActionResolutionEligibleActionsCount", 0L);
        assertThat(body).containsEntry("actionsWithPresentationHintsCount", 1L);
        assertThat(body).containsEntry("actionsWithBuiltInModuleMappingsCount", 1L);
        assertThat(body).containsEntry("actionsWithBuiltInCardMappingsCount", 1L);
        assertThat(body).containsEntry("actionsWithProvenanceCount", 1L);
        assertThat(body).containsEntry("confirmationInterceptorsCount", 1);
        assertThat(body).containsEntry("postActionWebhookPoliciesCount", 1L);
        assertThat(body).containsEntry("webhookTargetsCount", 1);
        assertThat(body.get("confirmationInterceptorRuleNames")).isEqualTo(List.of("cancel_to_retention_offer"));
        assertThat(body.get("confirmationInterceptorSources")).isEqualTo(List.of("classpath:test-actions.yml"));
        assertThat(body.get("actionNamesWithPostActionWebhookPolicies")).isEqualTo(List.of("create_purchase_order"));
        assertThat(body.get("webhookTargetIds")).isEqualTo(List.of("zapier_purchase_orders"));
        assertThat(body).containsEntry("knowledgeSourcesCount", 2);
        assertThat(body.get("knowledgeSourceIds")).isEqualTo(List.of("shared-catalog", "shared-policy"));
        assertThat(body.get("knowledgeSourceTypes")).isEqualTo(List.of("shared-vector", "shared-vector"));
        assertThat(body.get("knowledgeSourceAdapterTypes")).isEqualTo(List.of("shared-index"));
        assertThat(body).containsEntry("shellModulesCount", 2);
        assertThat(body.get("shellModuleIds")).isEqualTo(List.of("product-catalog", "policies"));
        assertThat(body).containsEntry("shellCardsCount", 1);
        assertThat(body.get("shellCardIds")).isEqualTo(List.of("policy-summary"));
        assertThat(body).containsEntry("shellStarterPromptsCount", 2);
        assertThat(body).containsEntry("shellGreetingConfigured", true);
        assertThat(body.get("supportedEntityTypes")).isEqualTo(Set.of("product", "policy", "review"));
        assertThat(body.get("vectorScope")).isEqualTo(vectorScope);
        assertThat(body.get("inferenceProfile")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> inferenceProfile = (Map<String, Object>) body.get("inferenceProfile");
        assertThat(inferenceProfile).containsEntry("llmProvider", "openai");
        assertThat(inferenceProfile).containsEntry("embeddingProvider", "onnx");
        assertThat(inferenceProfile).containsEntry("embeddingEndpointProfile", "onnx-bundled");
        assertThat(inferenceProfile).containsEntry("embeddingHasConnectionOverride", true);
        assertThat(inferenceProfile).containsEntry("orchestrationProvider", "openai");
        assertThat(inferenceProfile).containsEntry("orchestrationModel", "gpt-4o-mini");
        assertThat(inferenceProfile).containsEntry("orchestrationEndpointProfile", "openai-cloud-orchestration");
        assertThat(inferenceProfile).containsEntry("orchestrationHasConnectionOverride", true);
        assertThat(inferenceProfile).containsEntry("generationProvider", "openai");
        assertThat(inferenceProfile).containsEntry("generationModel", "gpt-4o");
        assertThat(inferenceProfile).containsEntry("generationEndpointProfile", "openai-cloud-default");
        assertThat(inferenceProfile).containsEntry("generationHasConnectionOverride", true);
        assertThat(body.get("searchSourceDiagnostics")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> searchSourceDiagnostics = (Map<String, Object>) body.get("searchSourceDiagnostics");
        assertThat(searchSourceDiagnostics).containsEntry("contractVersion", "SEARCH_SOURCE_DIAGNOSTICS_V1");
        assertThat(searchSourceDiagnostics).containsEntry("degradedSearchSupported", true);
        assertThat(searchSourceDiagnostics).containsEntry("configuredSourcesCount", 2);
        assertThat(searchSourceDiagnostics).containsEntry("degradedSourcesCount", 1L);
        assertThat(searchSourceDiagnostics).containsEntry("disabledSourcesCount", 0L);
        assertThat(searchSourceDiagnostics.get("sources")).isEqualTo(List.of(
            Map.of(
                "sourceId", "deployment-private-vector",
                "healthStatus", "READY"
            ),
            Map.of(
                "sourceId", "shared-catalog",
                "healthStatus", "DEGRADED"
            )
        ));
        assertThat(body.get("marketplaceSupport")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> marketplaceSupport = (Map<String, Object>) body.get("marketplaceSupport");
        assertThat(marketplaceSupport).containsEntry("contractVersion", "MARKETPLACE_RUNTIME_SUPPORT_V2");
        assertThat(marketplaceSupport).containsEntry("resolvedActionMetadataSupported", true);
        assertThat(marketplaceSupport).containsEntry("postActionWebhookPoliciesSupported", true);
        assertThat(marketplaceSupport).containsEntry("postActionWebhookPolicyContractVersion", "ACTION_WEBHOOK_POLICY_V1");
        assertThat(marketplaceSupport).containsEntry("actionMetadataContractVersion", "ACTION_METADATA_V2");
        assertThat(marketplaceSupport).containsEntry("resolvedKnowledgeSourcesSupported", true);
        assertThat(marketplaceSupport).containsEntry("resolvedShellConfigSupported", true);
        assertThat(marketplaceSupport).containsEntry("resolvedSearchSourcesSupported", true);
        assertThat(marketplaceSupport).containsEntry("degradedSearchSupported", true);
        assertThat(marketplaceSupport).containsEntry("knowledgeSourceContractVersion", "KNOWLEDGE_SOURCE_CONFIG_V1");
        assertThat(marketplaceSupport).containsEntry("shellConfigContractVersion", "SHELL_CONFIG_V1");
        assertThat(marketplaceSupport).containsEntry("searchSourceContractVersion", "SEARCH_SOURCE_REGISTRY_V1");
        assertThat(marketplaceSupport).containsEntry("searchSourceDiagnosticsContractVersion", "SEARCH_SOURCE_DIAGNOSTICS_V1");
        assertThat(marketplaceSupport.get("supportedKnowledgeSourceAdapterTypes"))
            .isEqualTo(List.of("deployment-private-vector", "shared-index"));
        assertThat(marketplaceSupport.get("supportedShellModuleIds"))
            .isEqualTo(BuiltInShellCatalog.MODULE_IDS);
        assertThat(marketplaceSupport.get("supportedShellCardIds"))
            .isEqualTo(BuiltInShellCatalog.CARD_IDS);
        assertThat(marketplaceSupport.get("supportedEvidenceBlockIds"))
            .isEqualTo(BuiltInShellCatalog.EVIDENCE_BLOCK_IDS);
        assertThat(body.get("auth")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) body.get("auth");
        assertThat(auth).containsEntry("ingressMode", "VERIFIED_CONTEXT_REQUIRED");
        assertThat(auth).containsEntry("verifiedContextRequired", true);
        assertThat(auth).containsEntry("rejectConflictingRequestIdentity", true);
        assertThat(auth).containsEntry("rejectRequestIdentityWhenVerifiedContextPresent", true);
        assertThat(auth).containsEntry("trustedBackendConfigured", true);
        assertThat(auth).containsEntry("privateAssertionValidationConfigured", true);
        assertThat(auth).containsEntry("privateAssertionIssuerPolicyConfigured", true);
        assertThat(auth).containsEntry("privateAssertionAudiencePolicyConfigured", true);
        assertThat(auth).containsEntry("publicTokenValidationConfigured", true);
        assertThat(auth).containsEntry("publicTokenIssuer", "runtime-public-bootstrap");
        assertThat(auth).containsEntry("publicDefaultAudience", "storefront-chat");
        assertThat(auth.get("privateAssertionAcceptedIssuers")).isEqualTo(List.of("platform-poc:SESSION"));
        assertThat(auth.get("privateAssertionAcceptedAudiences")).isEqualTo(List.of("dep-auth"));
        assertThat(auth.get("publicAcceptedIssuers")).isEqualTo(List.of("commerce-app"));
        assertThat(auth.get("publicAcceptedAudiences")).isEqualTo(List.of("storefront-chat"));
        assertThat(auth.get("publicAnonymousGrantedScopes"))
            .isEqualTo(List.of("chat:query", "chat:suggestions", "chat:conversations"));
        assertThat(auth.get("publicAuthenticatedDefaultScopes"))
            .isEqualTo(List.of("chat:query", "chat:suggestions", "chat:conversations"));
        assertThat(auth.get("publicAuthenticatedAllowedScopes"))
            .isEqualTo(List.of("chat:query", "chat:suggestions", "chat:conversations"));
        assertThat(auth).containsEntry("publicAnonymousConversationHistoryAllowed", true);
        assertThat(auth).containsEntry("publicAuthenticatedConversationHistoryAllowed", true);
        assertThat(auth).containsEntry("privateAssertionAuthorizationHeader", "X-AIFABRIC-RUNTIME-AUTHORIZATION");
        assertThat(auth).containsEntry("privateAssertionTokenScheme", "Bearer");
        assertThat(auth.get("publicBootstrap")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> publicBootstrap = (Map<String, Object>) auth.get("publicBootstrap");
        assertThat(publicBootstrap).containsEntry("enabled", true);
        assertThat(publicBootstrap.get("allowedOrigins")).isEqualTo(List.of("https://storefront.example"));
        assertThat(auth.get("supportedChatEndpoints"))
            .isEqualTo(List.of(
                "/api/chat/me/query",
                "/api/chat/me/query-once",
                "/api/chat/me/suggestions",
                "/api/chat/me/auth-context",
                "/api/chat/me/shell-config",
                "/api/chat/me/conversations",
                "/api/chat/me/conversations/{conversationId}"
            ));
        assertThat(body.get("authWarnings")).isEqualTo(List.of());
    }

    @Test
    void authOverviewSurfacesValidationWarningsAndContractMetadata() {
        RuntimeAuthProperties authProperties = new RuntimeAuthProperties();
        authProperties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        authProperties.getPublicTokens().getBootstrap().setEnabled(true);
        authProperties.getPublicTokens().getBootstrap().setAllowMissingOrigin(true);
        RuntimeAuthProperties requestAuthProperties = new RuntimeAuthProperties();
        requestAuthProperties.getIngress().getTrustedBackend().setApiKeyValue("runtime-secret");
        requestAuthProperties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-secret");

        RuntimeAdminOverviewController controller = instantiateController(
            mock(AIActionRegistry.class),
            mock(RuntimeActionCatalogGateway.class),
            mock(AIEntityConfigurationLoader.class),
            new TestVectorDatabaseService(),
            authProperties,
            new RuntimeRequestAuthResolver(
                requestAuthProperties,
                new RuntimePrivateAssertionService(requestAuthProperties),
                null
            ),
            confirmationProvider(),
            webhookPolicyProvider(),
            knowledgeSourceConfigProvider(),
            shellConfigProvider(),
            searchSourceRegistryProvider()
        );

        ResponseEntity<?> response = controller.authOverview(
            authorizedRequest(requestAuthProperties, RuntimeAdminScopeCatalog.RUNTIME_AUTH_OVERVIEW)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("contractVersion", "RUNTIME_AUTH_OVERVIEW_V1");
        assertThat(body).containsEntry("errorCount", 5);
        assertThat(body.get("errors")).isEqualTo(List.of(
            "Runtime auth ingress mode is VERIFIED_CONTEXT_REQUIRED but no trusted backend API key is configured. Set ai.fabric.runtime.auth.ingress.trusted-backend.api-key-value before starting the runtime.",
            "Runtime auth ingress mode is VERIFIED_CONTEXT_REQUIRED but no private assertion signing key is configured. Set ai.fabric.runtime.auth.ingress.private-assertions.signing-key before starting the runtime.",
            "Runtime auth ingress mode is VERIFIED_CONTEXT_REQUIRED without ai.fabric.runtime.auth.ingress.accepted-issuers. Configure an explicit verified-issuer allowlist before starting the runtime.",
            "Runtime auth ingress mode is VERIFIED_CONTEXT_REQUIRED without ai.fabric.runtime.auth.ingress.accepted-audiences. Configure an explicit verified-audience allowlist before starting the runtime.",
            "Runtime public bootstrap is enabled but no public token signing key is configured. Set ai.fabric.runtime.auth.public-tokens.signing-key before enabling POST /api/public/chat/session."
        ));
        assertThat(body).containsEntry("warningCount", 2);
        assertThat(body.get("warnings")).isEqualTo(List.of(
            "Runtime public bootstrap is enabled without any allowed origins. Cross-origin anonymous bootstrap requests will be denied unless allowed origins are configured.",
            "Runtime public bootstrap is enabled with allow-missing-origin=true. Anonymous public bootstrap requests without an Origin header will be accepted; use only when the embedding environment cannot provide origin headers."
        ));
        assertThat(body.get("guidance"))
            .isEqualTo("Runtime auth posture is not production-ready. Resolve the listed auth errors before starting or exposing the runtime.");
        assertThat(body.get("auth")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> auth = (Map<String, Object>) body.get("auth");
        assertThat(auth).containsEntry("rejectConflictingRequestIdentity", true);
        assertThat(auth).containsEntry("rejectRequestIdentityWhenVerifiedContextPresent", true);
    }

    @Test
    void overviewKeepsActionOnlyDeploymentsDefaultSafeWithoutMarketplaceArtifacts() {
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        RuntimeActionCatalogGateway actionCatalogGateway = mock(RuntimeActionCatalogGateway.class);
        AIEntityConfigurationLoader entityConfigurationLoader = mock(AIEntityConfigurationLoader.class);
        VectorDatabaseService vectorDatabaseService = new TestVectorDatabaseService();
        RuntimeAuthProperties authProperties = new RuntimeAuthProperties();
        authProperties.getIngress().setMode(RuntimeAuthIngressMode.VERIFIED_CONTEXT_REQUIRED);
        authProperties.getIngress().getTrustedBackend().setApiKeyValue("runtime-secret");
        authProperties.getIngress().getPrivateAssertions().setSigningKey("private-assertion-secret");
        authProperties.getIngress().setAcceptedIssuers(List.of("platform-poc:SESSION"));
        authProperties.getIngress().setAcceptedAudiences(List.of("dep-auth"));

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(
            AIActionMetaData.builder()
                .name("list_products")
                .displayName("List Products")
                .category("commerce")
                .description("List products")
                .accessMode(ActionAccessMode.READ)
                .confirmationRequired(false)
                .build()
        ));
        when(actionCatalogGateway.getSources()).thenReturn(List.of());
        when(entityConfigurationLoader.getSupportedEntityTypes()).thenReturn(Set.of("product"));

        RuntimeAdminOverviewController controller = instantiateController(
            actionRegistry,
            actionCatalogGateway,
            entityConfigurationLoader,
            vectorDatabaseService,
            authProperties,
            new RuntimeRequestAuthResolver(authProperties, new RuntimePrivateAssertionService(authProperties), null),
            emptyProvider(ConfirmationInterceptorCatalogProvider.class),
            emptyProvider(ConnectorActionWebhookPolicyCatalog.class),
            emptyProvider(RuntimeDeploymentKnowledgeSourceConfigService.class),
            emptyProvider(RuntimeDeploymentShellConfigService.class),
            emptyProvider(SearchSourceRegistry.class)
        );

        ResponseEntity<?> response = controller.overview(
            authorizedRequest(authProperties, RuntimeAdminScopeCatalog.RUNTIME_ADMIN_OVERVIEW)
        );

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("actionsCount", 1L);
        assertThat(body).containsEntry("confirmationInterceptorsCount", 0);
        assertThat(body).containsEntry("postActionWebhookPoliciesCount", 0L);
        assertThat(body).containsEntry("webhookTargetsCount", 0);
        assertThat(body).containsEntry("knowledgeSourcesCount", 0);
        assertThat(body).containsEntry("knowledgeSourceIds", List.of());
        assertThat(body).containsEntry("knowledgeSourceTypes", List.of());
        assertThat(body).containsEntry("knowledgeSourceAdapterTypes", List.of());
        assertThat(body).containsEntry("shellModulesCount", 0);
        assertThat(body).containsEntry("shellModuleIds", List.of());
        assertThat(body).containsEntry("shellCardsCount", 0);
        assertThat(body).containsEntry("shellCardIds", List.of());
        assertThat(body).containsEntry("shellStarterPromptsCount", 0);
        assertThat(body).containsEntry("shellGreetingConfigured", false);
        assertThat(body).containsEntry("searchSourceDiagnostics", Map.of());
        assertThat(body.get("marketplaceSupport")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> marketplaceSupport = (Map<String, Object>) body.get("marketplaceSupport");
        assertThat(marketplaceSupport).containsEntry("contractVersion", "MARKETPLACE_RUNTIME_SUPPORT_V2");
        assertThat(marketplaceSupport).containsEntry("resolvedActionMetadataSupported", true);
        assertThat(marketplaceSupport).containsEntry("postActionWebhookPoliciesSupported", true);
        assertThat(marketplaceSupport).containsEntry("postActionWebhookPolicyContractVersion", "ACTION_WEBHOOK_POLICY_V1");
        assertThat(marketplaceSupport).containsEntry("resolvedKnowledgeSourcesSupported", false);
        assertThat(marketplaceSupport).containsEntry("resolvedShellConfigSupported", false);
        assertThat(marketplaceSupport).containsEntry("resolvedSearchSourcesSupported", false);
        assertThat(marketplaceSupport).containsEntry("degradedSearchSupported", false);
        assertThat(marketplaceSupport).containsEntry("knowledgeSourceContractVersion", "KNOWLEDGE_SOURCE_CONFIG_V1");
        assertThat(marketplaceSupport).containsEntry("shellConfigContractVersion", "SHELL_CONFIG_V1");
        assertThat(marketplaceSupport).containsEntry("searchSourceContractVersion", "SEARCH_SOURCE_REGISTRY_V1");
        assertThat(marketplaceSupport).containsEntry("searchSourceDiagnosticsContractVersion", "SEARCH_SOURCE_DIAGNOSTICS_V1");
        assertThat(marketplaceSupport).containsEntry("supportedKnowledgeSourceAdapterTypes", List.of());
    }

    private RuntimeAdminOverviewController instantiateController(AIActionRegistry actionRegistry,
                                                                 RuntimeActionCatalogGateway actionCatalogGateway,
                                                                 AIEntityConfigurationLoader entityConfigurationLoader,
                                                                 VectorDatabaseService vectorDatabaseService,
                                                                 RuntimeAuthProperties authProperties,
                                                                 RuntimeRequestAuthResolver runtimeRequestAuthResolver,
                                                                 ObjectProvider<ConfirmationInterceptorCatalogProvider> confirmationProvider,
                                                                 ObjectProvider<ConnectorActionWebhookPolicyCatalog> webhookPolicyProvider,
                                                                 ObjectProvider<RuntimeDeploymentKnowledgeSourceConfigService> knowledgeSourceProvider,
                                                                 ObjectProvider<RuntimeDeploymentShellConfigService> shellConfigProvider,
                                                                 ObjectProvider<SearchSourceRegistry> searchSourceRegistryProvider) {
        try {
            Constructor<?> constructor = RuntimeAdminOverviewController.class.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            return (RuntimeAdminOverviewController) constructor.newInstance(
                actionRegistry,
                actionCatalogGateway,
                entityConfigurationLoader,
                aiProviderConfig(),
                vectorDatabaseService,
                authProperties,
                runtimeRequestAuthResolver,
                confirmationProvider,
                webhookPolicyProvider,
                knowledgeSourceProvider,
                shellConfigProvider,
                searchSourceRegistryProvider
            );
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
    }

    private AIProviderConfig aiProviderConfig() {
        AIProviderConfig providerConfig = new AIProviderConfig();
        providerConfig.setLlmProvider("openai");
        providerConfig.setEmbeddingProvider("onnx");
        providerConfig.setEmbeddingEndpointProfile("onnx-bundled");

        AIProviderConfig.OrchestrationLlmConfig orchestration = new AIProviderConfig.OrchestrationLlmConfig();
        orchestration.setLlmProvider("openai");
        orchestration.setModel("gpt-4o-mini");
        orchestration.setEndpointProfile("openai-cloud-orchestration");
        orchestration.setBaseUrl("https://api.openai.com/v1");
        orchestration.setApiKey("platform-openai-key");
        providerConfig.setOrchestration(orchestration);

        AIProviderConfig.GenerationLlmConfig generation = new AIProviderConfig.GenerationLlmConfig();
        generation.setLlmProvider("openai");
        generation.setModel("gpt-4o");
        generation.setEndpointProfile("openai-cloud-default");
        generation.setBaseUrl("https://api.openai.com/v1");
        generation.setApiKey("platform-openai-key");
        providerConfig.setGeneration(generation);
        return providerConfig;
    }

    private ObjectProvider<ConfirmationInterceptorCatalogProvider> confirmationProvider() {
        ConfirmationInterceptorCatalogProvider provider = new ConfirmationInterceptorCatalogProvider() {
            @Override
            public List<ConfirmationInterceptorRule> getRules() {
                return List.of(new ConfirmationInterceptorRule(
                    "cancel_to_retention_offer",
                    new ConfirmationInterceptorTrigger(List.of("cancel_purchase_order"), com.ai.infrastructure.dto.IntentType.CONFIRMATION_POSITIVE, "_retentionOfferOffered"),
                    new ConfirmationInterceptorDecision(ConfirmationInterceptorDecisionType.PROMPT_ACTION, "offer_order_discount", Map.of(), null),
                    ConfirmationInterceptorStackPolicy.NONE
                ));
            }

            @Override
            public List<String> getSourceLocations() {
                return List.of("classpath:test-actions.yml");
            }
        };
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("confirmationInterceptorCatalogProvider", provider);
        return beanFactory.getBeanProvider(ConfirmationInterceptorCatalogProvider.class);
    }

    private <T> ObjectProvider<T> emptyProvider(Class<T> type) {
        return new StaticListableBeanFactory().getBeanProvider(type);
    }

    private ObjectProvider<ConnectorActionWebhookPolicyCatalog> webhookPolicyProvider() {
        ConnectorActionWebhookPolicyCatalog provider = mock(ConnectorActionWebhookPolicyCatalog.class);
        when(provider.postPolicyCount()).thenReturn(1L);
        when(provider.actionNamesWithPostPolicies()).thenReturn(Set.of("create_purchase_order"));
        when(provider.webhookTargets()).thenReturn(List.of(
            new ConnectorWebhookTargetDefinition("zapier_purchase_orders", "ZAPIER_URL", "ZAPIER_SIGNING_SECRET", 3000, 5)
        ));
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("connectorActionWebhookPolicyCatalog", provider);
        return beanFactory.getBeanProvider(ConnectorActionWebhookPolicyCatalog.class);
    }

    private ObjectProvider<RuntimeDeploymentKnowledgeSourceConfigService> knowledgeSourceConfigProvider() {
        RuntimeDeploymentKnowledgeSourceConfigService service = mock(RuntimeDeploymentKnowledgeSourceConfigService.class);
        when(service.currentSourceCount()).thenReturn(2);
        when(service.currentSourceIds()).thenReturn(List.of("shared-catalog", "shared-policy"));
        when(service.currentSourceTypes()).thenReturn(List.of("shared-vector", "shared-vector"));
        when(service.currentSourceAdapterTypes()).thenReturn(List.of("shared-index"));
        when(service.currentContractVersion()).thenReturn("KNOWLEDGE_SOURCE_CONFIG_V1");
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("runtimeDeploymentKnowledgeSourceConfigService", service);
        return beanFactory.getBeanProvider(RuntimeDeploymentKnowledgeSourceConfigService.class);
    }

    private ObjectProvider<SearchSourceRegistry> searchSourceRegistryProvider() {
        SearchSourceRegistry registry = mock(SearchSourceRegistry.class);
        when(registry.contractVersion()).thenReturn("SEARCH_SOURCE_REGISTRY_V1");
        when(registry.supportedAdapterTypes()).thenReturn(List.of("deployment-private-vector", "shared-index"));
        when(registry.adminDiagnostics()).thenReturn(Map.of(
            "contractVersion", "SEARCH_SOURCE_DIAGNOSTICS_V1",
            "degradedSearchSupported", true,
            "configuredSourcesCount", 2,
            "degradedSourcesCount", 1L,
            "disabledSourcesCount", 0L,
            "sources", List.of(
                Map.of("sourceId", "deployment-private-vector", "healthStatus", "READY"),
                Map.of("sourceId", "shared-catalog", "healthStatus", "DEGRADED")
            )
        ));
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("runtimeDeploymentSearchSourceRegistry", registry);
        return beanFactory.getBeanProvider(SearchSourceRegistry.class);
    }

    private ObjectProvider<RuntimeDeploymentShellConfigService> shellConfigProvider() {
        RuntimeDeploymentShellConfigService service = mock(RuntimeDeploymentShellConfigService.class);
        when(service.currentModuleCount()).thenReturn(2);
        when(service.currentModuleIds()).thenReturn(List.of("product-catalog", "policies"));
        when(service.currentCardCount()).thenReturn(1);
        when(service.currentCardIds()).thenReturn(List.of("policy-summary"));
        when(service.currentStarterPromptCount()).thenReturn(2);
        when(service.currentGreetingMessage()).thenReturn("Ask about products or policy.");
        when(service.currentContractVersion()).thenReturn("SHELL_CONFIG_V1");
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("runtimeDeploymentShellConfigService", service);
        return beanFactory.getBeanProvider(RuntimeDeploymentShellConfigService.class);
    }

    private HttpServletRequest authorizedRequest(RuntimeAuthProperties authProperties, String scope) {
        RuntimePrivateAssertionService privateAssertionService = new RuntimePrivateAssertionService(authProperties);
        RuntimeAuthContext authContext = RuntimeAuthContext.builder()
            .subjectId("platform-admin")
            .subjectType(RuntimeAuthSubjectType.INTERNAL_PLATFORM_USER)
            .authMode(RuntimeAuthMode.PLATFORM_PROXY_SESSION)
            .callerType(RuntimeAuthCallerType.PLATFORM_PROXY)
            .deploymentId("dep-auth")
            .sessionId("platform-runtime-test")
            .issuer("platform-poc:SESSION")
            .audiences(List.of("dep-auth"))
            .expiresAt(Instant.now().plusSeconds(300))
            .grantedScopes(List.of(scope))
            .build();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-AIFABRIC-RUNTIME-API-KEY", "runtime-secret");
        request.addHeader(
            privateAssertionService.authorizationHeaderName(),
            privateAssertionService.tokenScheme() + " " + privateAssertionService.issueAssertion(authContext)
        );
        return request;
    }

    private static final class TestVectorDatabaseService implements VectorDatabaseService {
        private Map<String, Object> diagnostics = Map.of();

        @Override
        public boolean supportsVectorScan() {
            return true;
        }

        @Override
        public Map<String, Object> adminDiagnostics() {
            return diagnostics;
        }

        @Override
        public String storeVector(String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean updateVector(String vectorId, String entityType, String entityId, String content, List<Double> embedding, Map<String, Object> metadata) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VectorRecord> getVector(String vectorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<VectorRecord> getVectorByEntity(String entityType, String entityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AISearchResponse search(List<Double> queryVector, AISearchRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public AISearchResponse searchByEntityType(List<Double> queryVector, String entityType, int limit, double threshold) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeVector(String entityType, String entityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeVectorById(String vectorId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> batchStoreVectors(List<VectorRecord> vectors) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int batchUpdateVectors(List<VectorRecord> vectors) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int batchRemoveVectors(List<String> vectorIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<VectorRecord> getVectorsByEntityType(String entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long getVectorCountByEntityType(String entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long clearVectorsByEntityType(String entityType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long clearVectors() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, Object> getStatistics() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean vectorExists(String entityType, String entityId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public VectorScanPage scan(VectorScanRequest request) {
            throw new UnsupportedOperationException();
        }
    }
}
