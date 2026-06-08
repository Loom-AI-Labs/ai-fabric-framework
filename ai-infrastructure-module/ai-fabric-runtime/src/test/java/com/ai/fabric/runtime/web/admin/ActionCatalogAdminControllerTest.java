package com.ai.fabric.runtime.web.admin;

import com.ai.fabric.runtime.auth.RuntimeAuthCallerType;
import com.ai.fabric.runtime.auth.RuntimeAuthContext;
import com.ai.fabric.runtime.auth.RuntimeAuthMode;
import com.ai.fabric.runtime.auth.RuntimeAuthSubjectType;
import com.ai.fabric.runtime.auth.RuntimeRequestAuthResolver;
import com.ai.fabric.runtime.auth.RuntimeResolvedIdentity;
import com.ai.infrastructure.intent.action.AIActionMetaData;
import com.ai.infrastructure.intent.action.AIActionRegistry;
import com.ai.infrastructure.intent.action.AIContributionProvenance;
import com.ai.infrastructure.intent.action.ActionAccessMode;
import com.ai.infrastructure.intent.action.ActionResultPresentationHint;
import com.ai.infrastructure.intent.action.ActionSideEffectLevel;
import com.ai.infrastructure.intent.action.connector.ConnectorActionWebhookPolicyCatalog;
import com.ai.infrastructure.intent.action.connector.ConnectorWebhookTargetDefinition;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorCatalogProvider;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecision;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorDecisionType;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorRule;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorStackPolicy;
import com.ai.infrastructure.intent.action.confirmation.ConfirmationInterceptorTrigger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActionCatalogAdminControllerTest {

    @Test
    void overviewExposesEnrichedActionMetadataCounts() {
        AIActionRegistry actionRegistry = mock(AIActionRegistry.class);
        RuntimeRequestAuthResolver authResolver = mock(RuntimeRequestAuthResolver.class);
        ConfirmationInterceptorCatalogProvider confirmationProvider = mock(ConfirmationInterceptorCatalogProvider.class);
        ConnectorActionWebhookPolicyCatalog webhookPolicyCatalog = mock(ConnectorActionWebhookPolicyCatalog.class);

        RuntimeResolvedIdentity identity = RuntimeResolvedIdentity.builder()
            .authContext(RuntimeAuthContext.builder()
                .authMode(RuntimeAuthMode.PRIVATE_RUNTIME_BACKEND_MEDIATED)
                .callerType(RuntimeAuthCallerType.TRUSTED_BACKEND)
                .subjectType(RuntimeAuthSubjectType.INTERNAL_PLATFORM_USER)
                .subjectId("operator-1")
                .sessionId("session-1")
                .grantedScopes(List.of(RuntimeAdminScopeCatalog.RUNTIME_ACTIONS_OVERVIEW))
                .build())
            .warnings(List.of())
            .build();

        when(authResolver.resolveVerifiedPrivateContext(org.mockito.ArgumentMatchers.any(), eq("/api/admin/actions/overview")))
            .thenReturn(identity);
        doNothing().when(authResolver)
            .requireScope(eq(identity), eq(RuntimeAdminScopeCatalog.RUNTIME_ACTIONS_OVERVIEW), eq("/api/admin/actions/overview"));

        when(actionRegistry.getAllMetadata()).thenReturn(List.of(
            AIActionMetaData.builder()
                .name("create_purchase_order")
                .displayName("Create Purchase Order")
                .category("commerce")
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
        when(confirmationProvider.getRules()).thenReturn(List.of(
            new ConfirmationInterceptorRule(
                "cancel_to_retention_offer",
                new ConfirmationInterceptorTrigger(List.of("cancel_purchase_order"), com.ai.infrastructure.dto.IntentType.CONFIRMATION_POSITIVE, "_offered"),
                new ConfirmationInterceptorDecision(ConfirmationInterceptorDecisionType.PROMPT_ACTION, "offer_order_discount", Map.of(), null),
                new ConfirmationInterceptorStackPolicy(false, List.of())
            )
        ));
        when(confirmationProvider.getSourceLocations()).thenReturn(List.of("classpath:test-actions.yml"));
        when(webhookPolicyCatalog.postPolicyCount()).thenReturn(1L);
        when(webhookPolicyCatalog.actionNamesWithPostPolicies()).thenReturn(java.util.Set.of("create_purchase_order"));
        when(webhookPolicyCatalog.webhookTargets()).thenReturn(List.of(
            new ConnectorWebhookTargetDefinition("zapier_purchase_orders", "ZAPIER_URL", "ZAPIER_SIGNING_SECRET", 3000, 5)
        ));

        ActionCatalogAdminController controller = new ActionCatalogAdminController(
            actionRegistry,
            authResolver,
            fixedProvider(confirmationProvider),
            fixedProvider(webhookPolicyCatalog)
        );

        ResponseEntity<?> response = controller.overview(new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("success", true);
        assertThat(body).containsEntry("contractVersion", "RUNTIME_ACTION_CATALOG_OVERVIEW_V3");
        assertThat(body).containsEntry("count", 1);
        assertThat(body).containsEntry("groundingEligibleCount", 0L);
        assertThat(body).containsEntry("readActionResolutionEligibleCount", 0L);
        assertThat(body).containsEntry("withPresentationHintsCount", 1L);
        assertThat(body).containsEntry("withBuiltInModuleMappingsCount", 1L);
        assertThat(body).containsEntry("withBuiltInCardMappingsCount", 1L);
        assertThat(body).containsEntry("withProvenanceCount", 1L);
        assertThat(body).containsEntry("confirmationInterceptorsCount", 1);
        assertThat(body).containsEntry("postActionWebhookPoliciesCount", 1L);
        assertThat(body).containsEntry("webhookTargetsCount", 1);
        assertThat(body.get("confirmationInterceptorRuleNames")).isEqualTo(List.of("cancel_to_retention_offer"));
        assertThat(body.get("actionNamesWithPostActionWebhookPolicies")).isEqualTo(List.of("create_purchase_order"));
        assertThat(body.get("webhookTargetIds")).isEqualTo(List.of("zapier_purchase_orders"));
    }

    private <T> ObjectProvider<T> fixedProvider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }

            @Override
            public T getObject() {
                return value;
            }
        };
    }
}
