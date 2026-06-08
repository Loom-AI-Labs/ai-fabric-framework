package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.admin.RuntimeActionCatalogGateway;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.config.RuntimeAuthProperties;
import com.ai.fabric.runtime.config.RuntimeAuthStartupValidator;
import com.ai.fabric.runtime.config.RuntimeDeploymentKnowledgeSourceConfigService;
import com.ai.fabric.runtime.config.RuntimeDeploymentShellConfigService;
import com.ai.infrastructure.config.AIProviderConfig;
import com.ai.infrastructure.config.AIEntityConfigurationLoader;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.connector.ConnectorActionWebhookPolicyCatalog;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import com.ai.infrastructure.rag.VectorDatabaseService;
import com.ai.infrastructure.rag.source.SearchSourceRegistry;
import com.ai.infrastructure.shell.BuiltInShellCatalog;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class RuntimeAdminOverviewController {

    private final AIActionRegistry actionRegistry;
    private final RuntimeActionCatalogGateway actionCatalogGateway;
    private final AIEntityConfigurationLoader entityConfigurationLoader;
    private final AIProviderConfig aiProviderConfig;
    private final VectorDatabaseService vectorDatabaseService;
    private final RuntimeAuthProperties runtimeAuthProperties;
    private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;
    private final ObjectProvider<ConfirmationInterceptorCatalogProvider> confirmationInterceptorCatalogProvider;
    private final ObjectProvider<ConnectorActionWebhookPolicyCatalog> webhookPolicyCatalogProvider;
    private final ObjectProvider<RuntimeDeploymentKnowledgeSourceConfigService> knowledgeSourceConfigServiceProvider;
    private final ObjectProvider<RuntimeDeploymentShellConfigService> shellConfigServiceProvider;
    private final ObjectProvider<SearchSourceRegistry> searchSourceRegistryProvider;

    @Value("${ai.config.default-file:ai-entity-config.yml}")
    private String entityConfigLocation;

    @Value("${ai.prompts.deployment.config-file:}")
    private String promptConfigLocation;

    @Value("${ai.knowledge-sources.deployment.config-file:}")
    private String knowledgeSourceConfigLocation;

    @Value("${ai.shell.deployment.config-file:}")
    private String shellConfigLocation;

    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpServletRequest httpRequest) {
        authorize(httpRequest, RuntimeAdminScopeCatalog.RUNTIME_ADMIN_OVERVIEW, "/api/admin/overview");

        List<AIActionMetaData> actions = actionRegistry != null ? actionRegistry.getAllMetadata() : List.of();
        long actionCount = actions.stream()
            .filter(action -> action != null && StringUtils.hasText(action.getName()))
            .count();
        long groundingEligibleActionCount = actions.stream()
            .filter(action -> action != null && action.isGroundingEligible())
            .count();
        long readActionResolutionEligibleActionCount = actions.stream()
            .filter(action -> action != null && action.isReadActionResolutionEligible())
            .count();
        long presentationHintedActionCount = actions.stream()
            .filter(action -> action != null
                && action.getResultPresentationHint() != null
                && action.getResultPresentationHint() != com.ai.infrastructure.intent.action.ActionResultPresentationHint.DEFAULT)
            .count();
        long moduleMappedActionCount = actions.stream()
            .filter(action -> action != null && StringUtils.hasText(action.getBuiltInModuleId()))
            .count();
        long cardMappedActionCount = actions.stream()
            .filter(action -> action != null && StringUtils.hasText(action.getBuiltInCardId()))
            .count();
        long actionProvenanceCount = actions.stream()
            .filter(action -> action != null && action.getProvenance() != null)
            .count();

        Set<String> entityTypes = entityConfigurationLoader != null
            ? entityConfigurationLoader.getSupportedEntityTypes()
            : Set.of();

        List<Map<String, Object>> sources = actionCatalogGateway != null
            ? actionCatalogGateway.getSources().stream()
                .map(source -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("type", source.getType());
                    item.put("path", source.getPath());
                    item.put("optional", source.isOptional());
                    return item;
                })
                .toList()
            : List.of();

        Map<String, Object> body = new LinkedHashMap<>();
        ConfirmationInterceptorCatalogProvider confirmationProvider = confirmationInterceptorCatalogProvider.getIfAvailable();
        RuntimeDeploymentKnowledgeSourceConfigService knowledgeSourceConfigService = knowledgeSourceConfigServiceProvider.getIfAvailable();
        RuntimeDeploymentShellConfigService shellConfigService = shellConfigServiceProvider.getIfAvailable();
        ConnectorActionWebhookPolicyCatalog webhookPolicyCatalog = webhookPolicyCatalogProvider.getIfAvailable();
        SearchSourceRegistry searchSourceRegistry = searchSourceRegistryProvider.getIfAvailable();
        Map<String, Object> searchSourceDiagnostics = searchSourceRegistry != null
            ? searchSourceRegistry.adminDiagnostics()
            : Map.of();
        body.put("success", true);
        body.put("entityConfigLocation", entityConfigLocation);
        body.put("promptConfigLocation", promptConfigLocation);
        body.put("knowledgeSourceConfigLocation", knowledgeSourceConfigLocation);
        body.put("shellConfigLocation", shellConfigLocation);
        body.put("actionCatalogSources", sources);
        body.put("actionsCount", actionCount);
        body.put("groundingEligibleActionsCount", groundingEligibleActionCount);
        body.put("readActionResolutionEligibleActionsCount", readActionResolutionEligibleActionCount);
        body.put("actionsWithPresentationHintsCount", presentationHintedActionCount);
        body.put("actionsWithBuiltInModuleMappingsCount", moduleMappedActionCount);
        body.put("actionsWithBuiltInCardMappingsCount", cardMappedActionCount);
        body.put("actionsWithProvenanceCount", actionProvenanceCount);
        body.put("confirmationInterceptorsCount", confirmationProvider != null ? confirmationProvider.getRules().size() : 0);
        body.put("confirmationInterceptorRuleNames", confirmationProvider != null
            ? confirmationProvider.getRules().stream().map(rule -> rule != null ? rule.name() : null).filter(StringUtils::hasText).toList()
            : List.of());
        body.put("confirmationInterceptorSources", confirmationProvider != null ? confirmationProvider.getSourceLocations() : List.of());
        body.put("postActionWebhookPoliciesCount", webhookPolicyCatalog != null ? webhookPolicyCatalog.postPolicyCount() : 0L);
        body.put(
            "actionNamesWithPostActionWebhookPolicies",
            webhookPolicyCatalog != null ? List.copyOf(webhookPolicyCatalog.actionNamesWithPostPolicies()) : List.of()
        );
        body.put("webhookTargetsCount", webhookPolicyCatalog != null ? webhookPolicyCatalog.webhookTargets().size() : 0);
        body.put(
            "webhookTargetIds",
            webhookPolicyCatalog != null
                ? webhookPolicyCatalog.webhookTargets().stream()
                    .map(target -> target != null ? target.id() : null)
                    .filter(StringUtils::hasText)
                    .toList()
                : List.of()
        );
        body.put("supportedEntityTypes", entityTypes);
        body.put("vectorDb", vectorDatabaseService.getClass().getSimpleName());
        body.put("supportsVectorScan", vectorDatabaseService.supportsVectorScan());
        body.put("vectorScope", vectorDatabaseService.adminDiagnostics());
        body.put("inferenceProfile", inferenceProfile(aiProviderConfig));
        body.put("knowledgeSourcesCount", knowledgeSourceConfigService != null ? knowledgeSourceConfigService.currentSourceCount() : 0);
        body.put("knowledgeSourceIds", knowledgeSourceConfigService != null ? knowledgeSourceConfigService.currentSourceIds() : List.of());
        body.put("knowledgeSourceTypes", knowledgeSourceConfigService != null ? knowledgeSourceConfigService.currentSourceTypes() : List.of());
        body.put("knowledgeSourceAdapterTypes", knowledgeSourceConfigService != null ? knowledgeSourceConfigService.currentSourceAdapterTypes() : List.of());
        body.put("shellModulesCount", shellConfigService != null ? shellConfigService.currentModuleCount() : 0);
        body.put("shellModuleIds", shellConfigService != null ? shellConfigService.currentModuleIds() : List.of());
        body.put("shellCardsCount", shellConfigService != null ? shellConfigService.currentCardCount() : 0);
        body.put("shellCardIds", shellConfigService != null ? shellConfigService.currentCardIds() : List.of());
        body.put("shellStarterPromptsCount", shellConfigService != null ? shellConfigService.currentStarterPromptCount() : 0);
        body.put("shellGreetingConfigured", shellConfigService != null && StringUtils.hasText(shellConfigService.currentGreetingMessage()));
        body.put("searchSourceDiagnostics", searchSourceDiagnostics);
        body.put("marketplaceSupport", marketplaceSupport(knowledgeSourceConfigService, shellConfigService, searchSourceRegistry, searchSourceDiagnostics));
        body.put("auth", authDiagnostics(runtimeAuthProperties));
        body.put("authWarnings", authWarnings(runtimeAuthProperties));
        return ResponseEntity.ok(body);
    }

    @GetMapping("/auth/overview")
    public ResponseEntity<?> authOverview(HttpServletRequest httpRequest) {
        authorize(httpRequest, RuntimeAdminScopeCatalog.RUNTIME_AUTH_OVERVIEW, "/api/admin/auth/overview");
        return ResponseEntity.ok(buildAuthOverviewBody(runtimeAuthProperties));
    }

    private void authorize(HttpServletRequest request, String scope, String surface) {
        runtimeRequestAuthResolver.requireScope(
            runtimeRequestAuthResolver.resolveVerifiedPrivateContext(request, surface),
            scope,
            surface
        );
    }

    private static Map<String, Object> buildAuthOverviewBody(RuntimeAuthProperties properties) {
        Map<String, Object> body = new LinkedHashMap<>();
        Map<String, Object> auth = authDiagnostics(properties);
        List<String> errors = authErrors(properties);
        List<String> warnings = authWarnings(properties);
        body.put("success", true);
        body.put("contractVersion", "RUNTIME_AUTH_OVERVIEW_V1");
        body.put("auth", auth);
        body.put("errors", errors);
        body.put("errorCount", errors.size());
        body.put("warnings", warnings);
        body.put("warningCount", warnings.size());
        body.put("guidance",
            errors.isEmpty()
                    && Boolean.TRUE.equals(auth.get("trustedBackendConfigured"))
                    && Boolean.TRUE.equals(auth.get("privateAssertionValidationConfigured"))
                ? "Runtime auth posture is configured for signed private assertions and public/browser tokens where enabled. Prefer /api/chat/me/* and runtime-backed admin surfaces."
                : "Runtime auth posture is not production-ready. Resolve the listed auth errors before starting or exposing the runtime.");
        return body;
    }

    private static Map<String, Object> authDiagnostics(RuntimeAuthProperties properties) {
        Map<String, Object> out = new LinkedHashMap<>();
        RuntimeAuthProperties.Ingress ingress = properties != null ? properties.getIngress() : new RuntimeAuthProperties.Ingress();
        RuntimeAuthProperties.PublicTokens publicTokens = properties != null ? properties.getPublicTokens() : new RuntimeAuthProperties.PublicTokens();
        RuntimeAuthProperties.PrivateAssertions privateAssertions = ingress.getPrivateAssertions();

        Map<String, Object> bootstrap = new LinkedHashMap<>();
        bootstrap.put("enabled", publicTokens.getBootstrap().isEnabled());
        bootstrap.put("allowMissingOrigin", publicTokens.getBootstrap().isAllowMissingOrigin());
        bootstrap.put("allowedOrigins", List.copyOf(publicTokens.getBootstrap().getAllowedOrigins()));
        bootstrap.put("maxRequestsPerWindow", publicTokens.getBootstrap().getMaxRequestsPerWindow());
        bootstrap.put("rateLimitWindowSeconds", publicTokens.getBootstrap().getRateLimitWindowSeconds());

        out.put("ingressMode", ingress.getMode() != null ? ingress.getMode().name() : null);
        out.put("verifiedContextRequired", true);
        out.put("rejectConflictingRequestIdentity", ingress.isRejectConflictingRequestIdentity());
        out.put("rejectRequestIdentityWhenVerifiedContextPresent", ingress.isRejectRequestIdentityWhenVerifiedContextPresent());
        out.put("trustedBackendHeader", ingress.getTrustedBackend().getApiKeyHeader());
        out.put("trustedBackendConfigured", StringUtils.hasText(ingress.getTrustedBackend().getApiKeyValue()));
        out.put("privateAssertionAuthorizationHeader", privateAssertions.getAuthorizationHeader());
        out.put("privateAssertionTokenScheme", privateAssertions.getTokenScheme());
        out.put("privateAssertionValidationConfigured", StringUtils.hasText(privateAssertions.getSigningKey()));
        out.put("privateAssertionAcceptedIssuers", List.copyOf(ingress.getAcceptedIssuers()));
        out.put("privateAssertionAcceptedAudiences", List.copyOf(ingress.getAcceptedAudiences()));
        out.put("privateAssertionIssuerPolicyConfigured", ingress.getAcceptedIssuers().stream().anyMatch(StringUtils::hasText));
        out.put("privateAssertionAudiencePolicyConfigured", ingress.getAcceptedAudiences().stream().anyMatch(StringUtils::hasText));
        out.put("publicTokenValidationConfigured", StringUtils.hasText(publicTokens.getSigningKey()));
        out.put("publicAuthorizationHeader", publicTokens.getAuthorizationHeader());
        out.put("publicTokenScheme", publicTokens.getTokenScheme());
        out.put("publicTokenIssuer", publicTokens.getIssuer());
        out.put("publicAcceptedIssuers", List.copyOf(publicTokens.getAcceptedIssuers()));
        out.put("publicAcceptedAudiences", List.copyOf(publicTokens.getAcceptedAudiences()));
        out.put("publicDefaultAudience", publicTokens.getDefaultAudience());
        out.put("publicTokenTtlSeconds", publicTokens.getTtlSeconds());
        out.put("publicAnonymousGrantedScopes", List.copyOf(publicTokens.getAnonymousGrantedScopes()));
        out.put("publicAuthenticatedDefaultScopes", List.copyOf(publicTokens.getAuthenticatedDefaultScopes()));
        out.put("publicAuthenticatedAllowedScopes", List.copyOf(publicTokens.getAuthenticatedAllowedScopes()));
        out.put("publicAnonymousConversationHistoryAllowed", publicTokens.getAnonymousGrantedScopes().contains("chat:conversations"));
        out.put("publicAuthenticatedConversationHistoryAllowed", publicTokens.getAuthenticatedAllowedScopes().contains("chat:conversations"));
        out.put("publicBootstrap", bootstrap);
        out.put("supportedChatEndpoints", List.of(
            "/api/chat/me/query",
            "/api/chat/me/query-once",
            "/api/chat/me/suggestions",
            "/api/chat/me/auth-context",
            "/api/chat/me/shell-config",
            "/api/chat/me/conversations",
            "/api/chat/me/conversations/{conversationId}"
        ));
        return out;
    }

    private static List<String> authWarnings(RuntimeAuthProperties properties) {
        return new RuntimeAuthStartupValidator(properties).validationWarnings();
    }

    private static List<String> authErrors(RuntimeAuthProperties properties) {
        return new RuntimeAuthStartupValidator(properties).validationErrors();
    }

    private Map<String, Object> marketplaceSupport(RuntimeDeploymentKnowledgeSourceConfigService knowledgeSourceConfigService,
                                                   RuntimeDeploymentShellConfigService shellConfigService,
                                                   SearchSourceRegistry searchSourceRegistry,
                                                   Map<String, Object> searchSourceDiagnostics) {
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("contractVersion", "MARKETPLACE_RUNTIME_SUPPORT_V2");
        support.put("resolvedKnowledgeSourcesSupported", knowledgeSourceConfigService != null);
        support.put("resolvedShellConfigSupported", shellConfigService != null);
        support.put("resolvedActionMetadataSupported", true);
        support.put("postActionWebhookPoliciesSupported", true);
        support.put("postActionWebhookPolicyContractVersion", "ACTION_WEBHOOK_POLICY_V1");
        support.put("resolvedSearchSourcesSupported", searchSourceRegistry != null);
        support.put("degradedSearchSupported", searchSourceRegistry != null);
        support.put("actionMetadataContractVersion", "ACTION_METADATA_V2");
        support.put(
            "knowledgeSourceContractVersion",
            knowledgeSourceConfigService != null
                ? knowledgeSourceConfigService.currentContractVersion()
                : RuntimeDeploymentKnowledgeSourceConfigService.CONTRACT_VERSION
        );
        support.put(
            "shellConfigContractVersion",
            shellConfigService != null
                ? shellConfigService.currentContractVersion()
                : RuntimeDeploymentShellConfigService.CONTRACT_VERSION
        );
        support.put(
            "searchSourceContractVersion",
            searchSourceRegistry != null
                ? searchSourceRegistry.contractVersion()
                : "SEARCH_SOURCE_REGISTRY_V1"
        );
        support.put(
            "searchSourceDiagnosticsContractVersion",
            searchSourceDiagnostics.getOrDefault("contractVersion", "SEARCH_SOURCE_DIAGNOSTICS_V1")
        );
        support.put(
            "supportedKnowledgeSourceAdapterTypes",
            searchSourceRegistry != null
                ? searchSourceRegistry.supportedAdapterTypes()
                : List.of()
        );
        support.put("inferenceProfileContractVersion", "INFERENCE_PROFILE_RUNTIME_V1");
        support.put("supportedShellModuleIds", BuiltInShellCatalog.MODULE_IDS);
        support.put("supportedShellCardIds", BuiltInShellCatalog.CARD_IDS);
        support.put("supportedEvidenceBlockIds", BuiltInShellCatalog.EVIDENCE_BLOCK_IDS);
        return support;
    }

    private Map<String, Object> inferenceProfile(AIProviderConfig providerConfig) {
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("llmProvider", providerConfig != null ? providerConfig.getLlmProvider() : null);
        profile.put("embeddingProvider", providerConfig != null ? providerConfig.getEmbeddingProvider() : null);
        profile.put("embeddingEndpointProfile", providerConfig != null ? providerConfig.getEmbeddingEndpointProfile() : null);
        profile.put("embeddingManagedServiceRef", providerConfig != null ? providerConfig.getEmbeddingManagedServiceRef() : null);
        profile.put("embeddingServiceMode", providerConfig != null ? providerConfig.getEmbeddingServiceMode() : null);
        profile.put("embeddingHasConnectionOverride", providerConfig != null && providerConfig.embeddingHasConnectionOverride());
        profile.put("orchestrationProvider", providerConfig != null && providerConfig.getOrchestration() != null
            ? providerConfig.getOrchestration().getLlmProvider()
            : null);
        profile.put("orchestrationModel", providerConfig != null && providerConfig.getOrchestration() != null
            ? providerConfig.getOrchestration().getModel()
            : null);
        profile.put("orchestrationEndpointProfile", providerConfig != null && providerConfig.getOrchestration() != null
            ? providerConfig.getOrchestration().getEndpointProfile()
            : null);
        profile.put("orchestrationManagedServiceRef", providerConfig != null && providerConfig.getOrchestration() != null
            ? providerConfig.getOrchestration().getManagedServiceRef()
            : null);
        profile.put("orchestrationHasConnectionOverride", providerConfig != null && providerConfig.orchestrationHasConnectionOverride());
        profile.put("generationProvider", providerConfig != null && providerConfig.getGeneration() != null
            ? providerConfig.getGeneration().getLlmProvider()
            : null);
        profile.put("generationModel", providerConfig != null && providerConfig.getGeneration() != null
            ? providerConfig.getGeneration().getModel()
            : null);
        profile.put("generationEndpointProfile", providerConfig != null && providerConfig.getGeneration() != null
            ? providerConfig.getGeneration().getEndpointProfile()
            : null);
        profile.put("generationManagedServiceRef", providerConfig != null && providerConfig.getGeneration() != null
            ? providerConfig.getGeneration().getManagedServiceRef()
            : null);
        profile.put("generationHasConnectionOverride", providerConfig != null && providerConfig.generationHasConnectionOverride());
        return profile;
    }
}
