package ai.fabric.intent.orchestration;

import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSubjectRef;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.context.TrustedExecutionContext;
import java.util.List;
import java.util.Map;

/**
 * Resolves the canonical auth context from an orchestration request.
 */
public final class OrchestrationAuthContextResolver {

    private OrchestrationAuthContextResolver() {
    }

    public static AIAccessSubjectContext from(OrchestrationContext context) {
        Map<String, Object> metadata = context != null ? context.getMetadata() : null;
        String sessionId = context != null ? context.getSessionId() : null;
        String subjectId = resolveString(
            metadata,
            OrchestrationContextMetadataKeys.SUBJECT_ID,
            context != null ? context.getUserId() : null
        );
        if ((subjectId == null || subjectId.isBlank()) && sessionId != null && !sessionId.isBlank()) {
            subjectId = sessionId;
        }
        String subjectType = resolveString(metadata, OrchestrationContextMetadataKeys.SUBJECT_TYPE, null);
        if ((subjectType == null || subjectType.isBlank()) && subjectId != null && !subjectId.isBlank()) {
            subjectType = sessionId != null && sessionId.equals(subjectId) ? "ANONYMOUS_SESSION" : "END_USER";
        }
        return AIAccessSubjectContext.builder()
            .subjectId(subjectId)
            .sessionId(sessionId)
            .subjectType(subjectType)
            .authMode(resolveString(metadata, OrchestrationContextMetadataKeys.AUTH_MODE, null))
            .callerType(resolveString(metadata, OrchestrationContextMetadataKeys.CALLER_TYPE, null))
            .deploymentId(resolveString(metadata, OrchestrationContextMetadataKeys.DEPLOYMENT_ID, null))
            .customerId(resolveString(metadata, OrchestrationContextMetadataKeys.CUSTOMER_ID, null))
            .tenantId(resolveString(metadata, OrchestrationContextMetadataKeys.TENANT_ID, null))
            .issuer(resolveString(metadata, OrchestrationContextMetadataKeys.AUTH_ISSUER, null))
            .audiences(resolveStringList(metadata, OrchestrationContextMetadataKeys.AUTH_AUDIENCES))
            .grantedScopes(resolveStringList(metadata, OrchestrationContextMetadataKeys.GRANTED_SCOPES))
            .expiresAt(resolveString(metadata, OrchestrationContextMetadataKeys.AUTH_EXPIRES_AT, null))
            .build();
    }

    /**
     * Adapts server-owned execution identity to the existing access-control contract.
     */
    public static AIAccessSubjectContext from(TrustedExecutionContext context) {
        if (context == null) {
            return AIAccessSubjectContext.builder().build();
        }
        var caller = context.callerIdentity();
        boolean anonymous = context.initiator().principalType() == ExecutionPrincipalType.ANONYMOUS_SESSION;
        boolean callerIsSubject = context.source() == ExecutionSource.INTERACTIVE || caller != null;
        ExecutionSubjectRef subject = context.subject();
        String subjectId = !callerIsSubject && subject != null
            ? subject.subjectId()
            : context.initiator().principalId();
        String subjectType = !callerIsSubject && subject != null
            ? subject.subjectType()
            : context.initiator().principalType().name();
        return AIAccessSubjectContext.builder()
            .subjectId(subjectId)
            .subjectType(caller != null && caller.subjectType() != null ? caller.subjectType() : subjectType)
            .sessionId(caller != null && caller.sessionId() != null ? caller.sessionId()
                : anonymous ? context.initiator().principalId() : null)
            .customerId(caller != null ? caller.customerId() : null)
            .authMode(caller != null && caller.authMode() != null ? caller.authMode() : "TRUSTED_" + context.source().name())
            .callerType(caller != null && caller.callerType() != null ? caller.callerType() : context.initiator().principalType().name())
            .deploymentId(context.deploymentId())
            .tenantId(context.tenantId())
            .issuer(caller != null ? caller.issuer() : null)
            .audiences(caller != null ? caller.audiences() : null)
            .expiresAt(caller != null && caller.expiresAt() != null ? caller.expiresAt().toString() : null)
            .grantedScopes(List.copyOf(context.grantedScopes()))
            .build();
    }

    private static String resolveString(Map<String, Object> metadata, String key, String fallback) {
        if (metadata == null) {
            return fallback;
        }
        Object value = metadata.get(key);
        return value instanceof String text && !text.isBlank() ? text : fallback;
    }

    private static List<String> resolveStringList(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(text -> !text.isBlank())
                .toList();
        }
        return null;
    }
}
