package com.ai.fabric.realapps.agenticresolver.action.handler;

import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.intent.action.ActionContext;
import com.ai.fabric.realapps.agenticresolver.config.AgenticResolverTrustBoundary;
import com.ai.fabric.realapps.agenticresolver.entity.Subscription;
import com.ai.fabric.realapps.agenticresolver.service.SubscriptionService;
import com.ai.fabric.realapps.agenticresolver.service.UserService;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * Resolves account resources separately from the caller's conversation identity.
 */
@RequiredArgsConstructor
public abstract class BaseActionHandler {

    protected final UserService userService;

    /**
     * Parse userId string - supports both numeric (1-100) and UUID formats
     */
    protected UUID parseUserId(String userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        try {
            // Try as numeric ID (1-100)
            Long numericId = Long.parseLong(userId);
            if (numericId < 1 || numericId > 100) {
                throw new IllegalArgumentException("Numeric userId must be between 1 and 100");
            }
            return userService.getUserIdFromNumeric(numericId);
        } catch (NumberFormatException e) {
            // Try as UUID string
            return UUID.fromString(userId);
        }
    }

    protected UUID requireCurrentUser(ActionContext context) {
        TrustedExecutionContext trusted = context != null
            && context.pipelineContext() != null
            && context.pipelineContext().getOrchestrationRequest() != null
            ? context.pipelineContext().getOrchestrationRequest().trustedExecutionContext()
            : null;
        if (trusted != null) {
            ExecutionSubjectRef subject = trusted.subject();
            // Protect the server-bound account/tenant/deployment boundary. Never
            // substitute the initiator or model parameters for an invalid subject.
            if (subject == null || !"account".equals(subject.subjectType())
                || !AgenticResolverTrustBoundary.TENANT_ID.equals(trusted.tenantId())
                || !AgenticResolverTrustBoundary.DEPLOYMENT_ID.equals(trusted.deploymentId())) {
                throw new IllegalArgumentException("Trusted account subject in this demo boundary is required");
            }
            return UUID.fromString(subject.subjectId());
        }

        // The direct orchestration API uses application user IDs without a
        // specialist execution envelope; retain its numeric/UUID contract.
        String userId = context != null ? context.userId() : null;
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("authenticated user context is required");
        }
        return parseUserId(userId);
    }

    protected UUID requireActiveSubscriptionId(SubscriptionService subscriptionService, ActionContext context) {
        UUID userUuid = requireCurrentUser(context);
        return subscriptionService.getActiveSubscription(userUuid)
            .map(Subscription::getId)
            .orElseThrow(() -> new IllegalArgumentException("Active subscription not found for user"));
    }
}
