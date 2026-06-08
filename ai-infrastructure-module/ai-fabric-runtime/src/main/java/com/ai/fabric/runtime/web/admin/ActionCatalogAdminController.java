package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.connector.ConnectorActionWebhookPolicyCatalog;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints to inspect the loaded action catalog.
 *
 * <p>Useful for debugging "actions not loaded" issues in deployments where {@code ai-actions.yml}
 * might not be mounted/loaded.</p>
 */
@RestController
@RequestMapping("/api/admin/actions")
@RequiredArgsConstructor
public class ActionCatalogAdminController {

    private final AIActionRegistry actionRegistry;
    private final RuntimeRequestAuthResolver runtimeRequestAuthResolver;
    private final ObjectProvider<ConfirmationInterceptorCatalogProvider> confirmationInterceptorCatalogProvider;
    private final ObjectProvider<ConnectorActionWebhookPolicyCatalog> webhookPolicyCatalogProvider;

    @GetMapping("/overview")
    public ResponseEntity<?> overview(HttpServletRequest httpRequest) {
        runtimeRequestAuthResolver.requireScope(
            runtimeRequestAuthResolver.resolveVerifiedPrivateContext(httpRequest, "/api/admin/actions/overview"),
            RuntimeAdminScopeCatalog.RUNTIME_ACTIONS_OVERVIEW,
            "/api/admin/actions/overview"
        );

        List<AIActionMetaData> actions = actionRegistry != null ? actionRegistry.getAllMetadata() : List.of();
        actions = actions.stream()
            .filter(a -> a != null && StringUtils.hasText(a.getName()))
            .toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("contractVersion", "RUNTIME_ACTION_CATALOG_OVERVIEW_V3");
        body.put("count", actions.size());
        body.put("actions", actions);
        body.put("groundingEligibleCount", actions.stream().filter(AIActionMetaData::isGroundingEligible).count());
        body.put("readActionResolutionEligibleCount", actions.stream().filter(AIActionMetaData::isReadActionResolutionEligible).count());
        body.put("withPresentationHintsCount", actions.stream()
            .filter(action -> action.getResultPresentationHint() != null
                && action.getResultPresentationHint() != com.ai.infrastructure.intent.action.ActionResultPresentationHint.DEFAULT)
            .count());
        body.put("withBuiltInModuleMappingsCount", actions.stream().filter(action -> StringUtils.hasText(action.getBuiltInModuleId())).count());
        body.put("withBuiltInCardMappingsCount", actions.stream().filter(action -> StringUtils.hasText(action.getBuiltInCardId())).count());
        body.put("withProvenanceCount", actions.stream().filter(action -> action.getProvenance() != null).count());
        ConfirmationInterceptorCatalogProvider provider = confirmationInterceptorCatalogProvider.getIfAvailable();
        body.put("confirmationInterceptorsCount", provider != null ? provider.getRules().size() : 0);
        body.put("confirmationInterceptorRuleNames", provider != null
            ? provider.getRules().stream().map(rule -> rule != null ? rule.name() : null).filter(StringUtils::hasText).toList()
            : List.of());
        body.put("confirmationInterceptorSources", provider != null ? provider.getSourceLocations() : List.of());
        ConnectorActionWebhookPolicyCatalog webhookPolicyCatalog = webhookPolicyCatalogProvider.getIfAvailable();
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
        return ResponseEntity.ok(body);
    }
}
