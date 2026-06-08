package com.ai.infrastructure.intent.action;

import com.ai.infrastructure.dto.AIAccessSubjectContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationContext;
import com.ai.infrastructure.intent.orchestration.OrchestrationContextMetadataKeys;
import com.ai.infrastructure.intent.orchestration.pipeline.PipelineContext;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Execution context passed to action methods.
 */
public record ActionContext(OrchestrationContext orchestrationContext,
                            PipelineContext pipelineContext,
                            Map<String, Object> actionParams) {

    public ActionContext(OrchestrationContext orchestrationContext, PipelineContext pipelineContext) {
        this(orchestrationContext, pipelineContext, Map.of());
    }

    public ActionContext {
        actionParams = immutableActionParams(actionParams);
    }

    public ActionContext withActionParams(Map<String, Object> params) {
        return new ActionContext(orchestrationContext, pipelineContext, params);
    }

    public String userId() {
        return authContext().getSubjectId();
    }

    public String sessionId() {
        return orchestrationContext != null ? orchestrationContext.getSessionId() : null;
    }

    public String identifier() {
        return orchestrationContext != null ? orchestrationContext.getIdentifier() : null;
    }

    public String conversationId() {
        return orchestrationContext != null ? orchestrationContext.getConversationId() : null;
    }

    public String requestId() {
        return orchestrationContext != null ? orchestrationContext.getRequestId() : null;
    }

    public boolean hasConversation() {
        return orchestrationContext != null && orchestrationContext.hasConversation();
    }

    public Map<String, Object> metadata() {
        return orchestrationContext != null ? orchestrationContext.getMetadata() : Map.of();
    }

    public AIAccessSubjectContext authContext() {
        Map<String, Object> metadata = metadata();
        String subjectId = metadataText(metadata, OrchestrationContextMetadataKeys.SUBJECT_ID);
        String sessionId = sessionId();
        if (!StringUtils.hasText(subjectId) && orchestrationContext != null && StringUtils.hasText(orchestrationContext.getUserId())) {
            subjectId = orchestrationContext.getUserId().trim();
        }
        if (!StringUtils.hasText(subjectId)) {
            subjectId = StringUtils.hasText(sessionId) ? sessionId.trim() : null;
        }

        String subjectType = metadataText(metadata, OrchestrationContextMetadataKeys.SUBJECT_TYPE);
        if (!StringUtils.hasText(subjectType) && StringUtils.hasText(sessionId) && sessionId.trim().equals(subjectId)) {
            subjectType = "ANONYMOUS_SESSION";
        }
        if (!StringUtils.hasText(subjectType) && StringUtils.hasText(subjectId)) {
            subjectType = "END_USER";
        }

        return AIAccessSubjectContext.builder()
            .subjectId(trimToNull(subjectId))
            .sessionId(trimToNull(sessionId))
            .subjectType(trimToNull(subjectType))
            .authMode(metadataText(metadata, OrchestrationContextMetadataKeys.AUTH_MODE))
            .callerType(metadataText(metadata, OrchestrationContextMetadataKeys.CALLER_TYPE))
            .deploymentId(metadataText(metadata, OrchestrationContextMetadataKeys.DEPLOYMENT_ID))
            .customerId(metadataText(metadata, OrchestrationContextMetadataKeys.CUSTOMER_ID))
            .tenantId(metadataText(metadata, OrchestrationContextMetadataKeys.TENANT_ID))
            .issuer(metadataText(metadata, OrchestrationContextMetadataKeys.AUTH_ISSUER))
            .grantedScopes(metadataStringList(metadata, OrchestrationContextMetadataKeys.GRANTED_SCOPES))
            .audiences(metadataStringList(metadata, OrchestrationContextMetadataKeys.AUTH_AUDIENCES))
            .expiresAt(metadataText(metadata, OrchestrationContextMetadataKeys.AUTH_EXPIRES_AT))
            .build();
    }

    private String metadataText(Map<String, Object> metadata, String key) {
        if (metadata == null || !StringUtils.hasText(key)) {
            return null;
        }
        Object raw = metadata.get(key);
        if (raw == null) {
            return null;
        }
        return trimToNull(String.valueOf(raw));
    }

    private List<String> metadataStringList(Map<String, Object> metadata, String key) {
        if (metadata == null || !StringUtils.hasText(key)) {
            return List.of();
        }
        Object raw = metadata.get(key);
        if (!(raw instanceof Iterable<?> iterable)) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(iterable.spliterator(), false)
            .map(String::valueOf)
            .map(this::trimToNull)
            .filter(StringUtils::hasText)
            .distinct()
            .toList();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Map<String, Object> immutableActionParams(Map<String, Object> params) {
        if (params == null || params.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (entry == null || !StringUtils.hasText(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            copy.put(entry.getKey().trim(), entry.getValue());
        }
        return copy.isEmpty() ? Map.of() : Collections.unmodifiableMap(copy);
    }
}
