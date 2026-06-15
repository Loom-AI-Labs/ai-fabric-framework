package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.access.AIAccessControlService;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIAccessSubjectContext;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationContextMetadataKeys;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pipeline step that performs access control validation.
 * 
 * <p>This step verifies that the user/session has permission to access the
 * RAG orchestration service. It uses the {@link AIAccessControlService} to
 * evaluate access rights based on user identity, session, and request context.</p>
 * 
 * <p><strong>Order:</strong> 20 (after security analysis)</p>
 * 
 * <p><strong>Fail-Closed Security:</strong> If access is denied, the entire
 * request is rejected. The framework does not silently filter or degrade
 * service - access is either fully granted or fully denied.</p>
 * 
 * @see AIAccessControlService
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessControlStep implements PipelineStep {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "AccessControl";
    private static final int STEP_ORDER = 20;
    
    // Resource and operation identifiers
    private static final String RESOURCE_ID_RAG_INTENT = "rag:intent";
    private static final String OPERATION_TYPE_READ = "READ";
    
    // Error messages
    private static final String ERROR_MSG_ACCESS_DENIED = "Access denied by policy.";
    
    // Metadata keys
    private static final String METADATA_KEY_ENTRY_POINT = "entryPoint";
    private static final String METADATA_KEY_AUTHENTICATED = "authenticated";
    private static final String METADATA_KEY_SESSION_ID = "sessionId";
    private static final String METADATA_KEY_IP_ADDRESS = "ipAddress";
    
    // Metadata values
    private static final String ENTRY_POINT_RAG_ORCHESTRATOR = "RAG_ORCHESTRATOR";
    private static final List<String> VERIFIED_AUTH_METADATA_KEYS = List.of(
        OrchestrationContextMetadataKeys.SUBJECT_ID,
        OrchestrationContextMetadataKeys.SUBJECT_TYPE,
        OrchestrationContextMetadataKeys.AUTH_MODE,
        OrchestrationContextMetadataKeys.CALLER_TYPE,
        OrchestrationContextMetadataKeys.AUTH_ISSUER,
        OrchestrationContextMetadataKeys.DEPLOYMENT_ID,
        OrchestrationContextMetadataKeys.CUSTOMER_ID,
        OrchestrationContextMetadataKeys.TENANT_ID,
        OrchestrationContextMetadataKeys.GRANTED_SCOPES,
        OrchestrationContextMetadataKeys.REQUESTED_SCOPES
    );
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final AIAccessControlService accessControlService;
    
    // =========================================================================
    // PipelineStep Implementation
    // =========================================================================
    
    /**
     * {@inheritDoc}
     */
    @Override
    public String getStepName() {
        return STEP_NAME;
    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return STEP_ORDER;
    }
    
    /**
     * Perform access control validation.
     * 
     * <p>Creates an {@link AIAccessControlRequest} and checks if the user
     * has permission to access the RAG orchestration service. If access is
     * denied, the pipeline is terminated with an error.</p>
     * 
     * <p><strong>Fail-Closed:</strong> Any denial results in complete request
     * rejection. No partial access or silent filtering is performed.</p>
     * 
     * @param context the current pipeline context
     * @return updated context, or terminated context if access denied
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Checking access control for request {}", context.getRequestId());
        
        OrchestrationContext orchContext = context.getOrchestrationContext();
        
        AIAccessControlRequest accessRequest = AIAccessControlRequest.builder()
            .requestId(context.getRequestId())
            .authContext(buildAuthContext(orchContext))
            .resourceId(RESOURCE_ID_RAG_INTENT)
            .operationType(OPERATION_TYPE_READ)
            .context(context.getOriginalQuery())
            .metadata(buildAccessControlMetadata(orchContext))
            .ipAddress(orchContext.getIpAddress())
            .userAgent(orchContext.getUserAgent())
            .timestamp(context.getRequestTimestamp())
            .build();
        
        AIAccessControlResponse accessResponse = accessControlService.checkAccess(accessRequest);
        
        if (!Boolean.TRUE.equals(accessResponse.getAccessGranted())) {
            log.warn("Access denied for request {} - user: {}, resource: {}", 
                context.getRequestId(), context.getIdentifier(), RESOURCE_ID_RAG_INTENT);
            return context.terminate(OrchestrationResult.error(ERROR_MSG_ACCESS_DENIED));
        }
        
        log.debug("Access granted for request {}", context.getRequestId());
        return context;
    }
    
    // =========================================================================
    // Private Helper Methods
    // =========================================================================
    
    /**
     * Build metadata map for access control evaluation.
     * 
     * @param context the orchestration context
     * @return metadata map with access control relevant information
     */
    private Map<String, Object> buildAccessControlMetadata(OrchestrationContext context) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(METADATA_KEY_ENTRY_POINT, ENTRY_POINT_RAG_ORCHESTRATOR);
        metadata.put(METADATA_KEY_AUTHENTICATED, context.isAuthenticated());
        metadata.put(METADATA_KEY_SESSION_ID, context.getSessionId());
        
        if (context.getIpAddress() != null) {
            metadata.put(METADATA_KEY_IP_ADDRESS, context.getIpAddress());
        }

        if (context.getMetadata() != null && !context.getMetadata().isEmpty()) {
            for (String key : VERIFIED_AUTH_METADATA_KEYS) {
                Object value = context.getMetadata().get(key);
                if (value != null) {
                    metadata.put(key, value);
                }
            }
        }
        
        return metadata;
    }

    private AIAccessSubjectContext buildAuthContext(OrchestrationContext context) {
        Map<String, Object> metadata = context != null ? context.getMetadata() : null;
        String subjectId = stringMetadata(metadata, OrchestrationContextMetadataKeys.SUBJECT_ID);
        if (subjectId == null && context != null && context.getUserId() != null && !context.getUserId().isBlank()) {
            subjectId = context.getUserId().trim();
        }
        if (subjectId == null && context != null && context.getSessionId() != null && !context.getSessionId().isBlank()) {
            subjectId = context.getSessionId().trim();
        }
        String sessionId = context != null && context.getSessionId() != null && !context.getSessionId().isBlank()
            ? context.getSessionId().trim()
            : null;
        String subjectType = stringMetadata(metadata, OrchestrationContextMetadataKeys.SUBJECT_TYPE);
        if (subjectType == null) {
            subjectType = sessionId != null && sessionId.equals(subjectId) ? "ANONYMOUS_SESSION" : "END_USER";
        }
        return AIAccessSubjectContext.builder()
            .subjectId(subjectId)
            .sessionId(sessionId)
            .subjectType(subjectType)
            .authMode(stringMetadata(metadata, OrchestrationContextMetadataKeys.AUTH_MODE))
            .callerType(stringMetadata(metadata, OrchestrationContextMetadataKeys.CALLER_TYPE))
            .deploymentId(stringMetadata(metadata, OrchestrationContextMetadataKeys.DEPLOYMENT_ID))
            .customerId(stringMetadata(metadata, OrchestrationContextMetadataKeys.CUSTOMER_ID))
            .tenantId(stringMetadata(metadata, OrchestrationContextMetadataKeys.TENANT_ID))
            .issuer(stringMetadata(metadata, OrchestrationContextMetadataKeys.AUTH_ISSUER))
            .grantedScopes(listMetadata(metadata, OrchestrationContextMetadataKeys.GRANTED_SCOPES))
            .audiences(listMetadata(metadata, OrchestrationContextMetadataKeys.AUTH_AUDIENCES))
            .expiresAt(stringMetadata(metadata, OrchestrationContextMetadataKeys.AUTH_EXPIRES_AT))
            .build();
    }

    private String stringMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return null;
        }
        Object value = metadata.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString();
        return text.isBlank() ? null : text.trim();
    }

    @SuppressWarnings("unchecked")
    private List<String> listMetadata(Map<String, Object> metadata, String key) {
        if (metadata == null || key == null) {
            return List.of();
        }
        Object value = metadata.get(key);
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        return raw.stream()
            .filter(java.util.Objects::nonNull)
            .map(Object::toString)
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .toList();
    }
}
