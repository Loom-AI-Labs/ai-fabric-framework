package com.ai.fabric.runtime.webhook;

import com.ai.infrastructure.intent.action.ActionContext;
import com.ai.infrastructure.intent.action.ActionResult;
import com.ai.infrastructure.intent.action.ActionTargetRef;
import com.ai.infrastructure.intent.action.connector.ConnectorActionPostPolicyDefinition;
import com.ai.infrastructure.intent.action.connector.ConnectorActionWebhookPolicyCatalog;
import com.ai.infrastructure.intent.action.connector.ConnectorWebhookTargetDefinition;
import com.ai.infrastructure.intent.action.policy.ActionPostPolicyEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class RuntimeActionPostPolicyEngine implements ActionPostPolicyEngine {

    private static final int DEFAULT_TIMEOUT_MS = 3_000;
    private static final int DEFAULT_MAX_ATTEMPTS = 5;

    private final ConnectorActionWebhookPolicyCatalog webhookPolicyCatalog;
    private final ActionWebhookDeliveryRepository deliveryRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RuntimeActionPostPolicyEngine(ConnectorActionWebhookPolicyCatalog webhookPolicyCatalog,
                                         ActionWebhookDeliveryRepository deliveryRepository,
                                         ObjectMapper objectMapper,
                                         Clock clock) {
        this.webhookPolicyCatalog = webhookPolicyCatalog;
        this.deliveryRepository = deliveryRepository;
        this.objectMapper = objectMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public void handleSuccessfulAction(String actionName,
                                       Map<String, Object> actionParams,
                                       ActionResult actionResult,
                                       ActionContext actionContext) {
        if (!StringUtils.hasText(actionName) || actionResult == null || !actionResult.isSuccess()) {
            return;
        }
        Map<String, ConnectorWebhookTargetDefinition> targetsById = webhookPolicyCatalog.webhookTargetsById();
        List<ConnectorActionPostPolicyDefinition> postPolicies = webhookPolicyCatalog.postPoliciesForAction(actionName);
        if (postPolicies.isEmpty() || targetsById.isEmpty()) {
            return;
        }

        Instant now = Instant.now(clock);
        for (ConnectorActionPostPolicyDefinition policy : postPolicies) {
            if (policy == null || !StringUtils.hasText(policy.targetRef())) {
                continue;
            }
            ConnectorWebhookTargetDefinition target = targetsById.get(policy.targetRef().trim().toLowerCase(java.util.Locale.ROOT));
            if (target == null) {
                throw new IllegalStateException("Webhook target '" + policy.targetRef() + "' is not available at runtime.");
            }
            ActionWebhookDeliveryEntity delivery = new ActionWebhookDeliveryEntity();
            delivery.setId("whd-" + UUID.randomUUID());
            delivery.setActionName(actionName.trim());
            delivery.setEventType(policy.eventType());
            delivery.setTargetRef(target.id());
            delivery.setUrlSecretRef(target.urlSecretRef());
            delivery.setSigningSecretRef(target.signingSecretRef());
            delivery.setTimeoutMs(target.timeoutMs() != null ? target.timeoutMs() : DEFAULT_TIMEOUT_MS);
            delivery.setMaxAttempts(target.maxAttempts() != null ? target.maxAttempts() : DEFAULT_MAX_ATTEMPTS);
            delivery.setAttemptCount(0);
            delivery.setStatus(ActionWebhookDeliveryEntity.STATUS_PENDING);
            delivery.setDeploymentId(actionContext != null && actionContext.authContext() != null ? actionContext.authContext().getDeploymentId() : null);
            delivery.setConversationId(actionContext != null ? actionContext.conversationId() : null);
            delivery.setRequestId(actionContext != null ? actionContext.requestId() : null);
            delivery.setPayloadJson(writePayloadJson(policy, actionName, actionParams, actionResult, actionContext, now));
            delivery.setNextAttemptAt(now);
            delivery.setCreatedAt(now);
            delivery.setUpdatedAt(now);
            deliveryRepository.save(delivery);
        }
    }

    private String writePayloadJson(ConnectorActionPostPolicyDefinition policy,
                                    String actionName,
                                    Map<String, Object> actionParams,
                                    ActionResult actionResult,
                                    ActionContext actionContext,
                                    Instant now) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventType", policy.eventType());
            payload.put("timestamp", now.toString());
            payload.put("deploymentId", actionContext != null && actionContext.authContext() != null ? actionContext.authContext().getDeploymentId() : null);
            payload.put("conversationId", actionContext != null ? actionContext.conversationId() : null);
            payload.put("requestId", actionContext != null ? actionContext.requestId() : null);
            payload.put("action", Map.of(
                "name", actionName,
                "params", actionParams != null ? new LinkedHashMap<>(actionParams) : Map.of()
            ));
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", actionResult.isSuccess());
            result.put("message", actionResult.getMessage());
            if (actionResult.getData() != null) {
                result.put("data", actionResult.getData().toMap());
            }
            List<ActionTargetRef> pinnedTargets = actionResult.getPinnedTargets();
            if (pinnedTargets != null && !pinnedTargets.isEmpty()) {
                result.put("pinnedTargets", pinnedTargets);
            }
            payload.put("result", result);
            if (actionContext != null && actionContext.authContext() != null) {
                Map<String, Object> auth = new LinkedHashMap<>();
                auth.put("subjectId", actionContext.authContext().getSubjectId());
                auth.put("subjectType", actionContext.authContext().getSubjectType());
                auth.put("customerId", actionContext.authContext().getCustomerId());
                auth.put("tenantId", actionContext.authContext().getTenantId());
                auth.put("authMode", actionContext.authContext().getAuthMode());
                payload.put("auth", auth);
            }
            return objectMapper.writeValueAsString(payload);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize webhook delivery payload: " + ex.getMessage(), ex);
        }
    }
}
