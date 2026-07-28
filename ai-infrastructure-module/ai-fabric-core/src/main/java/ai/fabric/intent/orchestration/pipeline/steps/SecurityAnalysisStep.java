package ai.fabric.intent.orchestration.pipeline.steps;

import ai.fabric.dto.AISecurityRequest;
import ai.fabric.dto.AISecurityResponse;
import ai.fabric.intent.orchestration.OrchestrationContext;
import ai.fabric.intent.orchestration.OrchestrationAuthContextResolver;
import ai.fabric.intent.orchestration.OrchestrationResult;
import ai.fabric.intent.orchestration.pipeline.PipelineContext;
import ai.fabric.intent.orchestration.pipeline.PipelineStep;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.security.AISecurityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pipeline step that performs security analysis on incoming requests.
 * 
 * <p>This step is the first line of defense, analyzing requests for potential
 * security threats before any processing occurs. It uses the {@link AISecurityService}
 * to evaluate the request and can block suspicious activity.</p>
 * 
 * <p><strong>Order:</strong> 10 (first step in pipeline)</p>
 * 
 * <p><strong>Termination:</strong> If security analysis determines the request
 * should be blocked, this step terminates the pipeline with an error result.</p>
 * 
 * @see AISecurityService
 * @see PipelineStep
 * @since 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityAnalysisStep implements PipelineStep {
    
    // =========================================================================
    // Constants
    // =========================================================================
    
    private static final String STEP_NAME = "SecurityAnalysis";
    private static final int STEP_ORDER = 10;
    
    // Operation types
    private static final String OPERATION_TYPE_INTENT_QUERY = "INTENT_QUERY";
    
    // Error messages
    private static final String ERROR_MSG_SECURITY_BLOCKED = "Request blocked by security controls.";
    
    // Metadata keys
    private static final String METADATA_KEY_AUTHENTICATED = "authenticated";
    private static final String METADATA_KEY_SESSION_ID = "sessionId";
    private static final String METADATA_KEY_IP_ADDRESS = "ipAddress";
    private static final String METADATA_KEY_USER_AGENT = "userAgent";
    
    // =========================================================================
    // Dependencies
    // =========================================================================
    
    private final AISecurityService securityService;
    
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
     * Perform security analysis on the request.
     * 
     * <p>Creates an {@link AISecurityRequest} from the pipeline context and
     * submits it to the security service for analysis. If the security service
     * indicates the request should be blocked, the pipeline is terminated.</p>
     * 
     * @param context the current pipeline context
     * @return updated context, or terminated context if blocked
     */
    @Override
    public PipelineContext process(PipelineContext context) {
        log.debug("Executing security analysis for request {}", context.getRequestId());
        
        OrchestrationContext orchContext = context.getOrchestrationContext();
        TrustedExecutionContext trustedContext =
            context.getOrchestrationRequest() != null
                ? context.getOrchestrationRequest().trustedExecutionContext()
                : null;
        
        AISecurityRequest securityRequest = AISecurityRequest.builder()
            .requestId(context.getRequestId())
            .authContext(trustedContext != null
                ? OrchestrationAuthContextResolver.from(trustedContext)
                : OrchestrationAuthContextResolver.from(orchContext))
            .content(context.getOriginalQuery())
            .operationType(OPERATION_TYPE_INTENT_QUERY)
            .timestamp(context.getRequestTimestamp())
            .metadata(buildSecurityMetadata(orchContext, trustedContext))
            .ipAddress(orchContext.getIpAddress())
            .userAgent(orchContext.getUserAgent())
            .build();
        
        AISecurityResponse securityResponse = securityService.analyzeRequest(securityRequest);
        
        if (Boolean.TRUE.equals(securityResponse.getShouldBlock())) {
            log.warn("Security blocked request {} for user {}", 
                context.getRequestId(), context.getIdentifier());
            return context.terminate(OrchestrationResult.error(ERROR_MSG_SECURITY_BLOCKED));
        }
        
        log.debug("Security analysis passed for request {}", context.getRequestId());
        return context;
    }
    
    // =========================================================================
    // Private Helper Methods
    // =========================================================================
    
    /**
     * Build metadata map for security analysis.
     * 
     * @param context the orchestration context
     * @return metadata map with security-relevant information
     */
    private Map<String, Object> buildSecurityMetadata(
        OrchestrationContext context,
        TrustedExecutionContext trustedContext
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(
            METADATA_KEY_AUTHENTICATED,
            trustedContext != null || context.isAuthenticated()
        );
        metadata.put(METADATA_KEY_SESSION_ID, context.getSessionId());
        if (trustedContext != null) {
            metadata.put("executionSource", trustedContext.source().name());
            metadata.put("principalType", trustedContext.initiator().principalType().name());
            metadata.put("correlationId", trustedContext.correlationId());
        }
        
        if (context.getIpAddress() != null) {
            metadata.put(METADATA_KEY_IP_ADDRESS, context.getIpAddress());
        }
        if (context.getUserAgent() != null) {
            metadata.put(METADATA_KEY_USER_AGENT, context.getUserAgent());
        }
        if (context.getMetadata() != null && !context.getMetadata().isEmpty()) {
            metadata.putAll(context.getMetadata());
        }
        
        return metadata;
    }
}
