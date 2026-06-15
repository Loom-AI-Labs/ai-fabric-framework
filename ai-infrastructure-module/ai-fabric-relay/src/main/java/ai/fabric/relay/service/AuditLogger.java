package ai.fabric.relay.service;

import ai.fabric.relay.api.TraceContextDto;
import ai.fabric.relay.config.RelayProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class AuditLogger {

    private final RelayProperties properties;

    public AuditLogger(RelayProperties properties) {
        this.properties = properties;
    }

    public void logAction(String actionId,
                          TraceContextDto trace,
                          boolean success,
                          String errorCode,
                          long latencyMs) {
        if (properties == null || properties.getAudit() == null || !properties.getAudit().isEnabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("type", "action");
        event.put("actionId", actionId);
        event.put("requestId", trace != null ? trace.requestId() : null);
        event.put("conversationId", trace != null ? trace.conversationId() : null);
        event.put("subjectId", RelayTraceContextSupport.effectiveSubjectId(trace));
        event.put("subjectType", RelayTraceContextSupport.subjectType(trace));
        event.put("authMode", RelayTraceContextSupport.authMode(trace));
        event.put("callerType", RelayTraceContextSupport.callerType(trace));
        event.put("deploymentId", RelayTraceContextSupport.deploymentId(trace));
        event.put("customerId", RelayTraceContextSupport.customerId(trace));
        event.put("tenantId", RelayTraceContextSupport.tenantId(trace));
        event.put("issuer", RelayTraceContextSupport.authIssuer(trace));
        event.put("sessionId", RelayTraceContextSupport.effectiveSessionId(trace));
        if (!RelayTraceContextSupport.grantedScopes(trace).isEmpty()) {
            event.put("grantedScopes", RelayTraceContextSupport.grantedScopes(trace));
        }
        event.put("success", success);
        if (StringUtils.hasText(errorCode)) {
            event.put("errorCode", errorCode);
        }
        event.put("latencyMs", latencyMs);
        log.info("relay_audit {}", event);
    }

    public void logRetrieval(String vectorSpace,
                             TraceContextDto trace,
                             boolean success,
                             String errorCode,
                             long latencyMs) {
        if (properties == null || properties.getAudit() == null || !properties.getAudit().isEnabled()) {
            return;
        }
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("ts", Instant.now().toString());
        event.put("type", "retrieval");
        event.put("vectorSpace", vectorSpace);
        event.put("requestId", trace != null ? trace.requestId() : null);
        event.put("conversationId", trace != null ? trace.conversationId() : null);
        event.put("subjectId", RelayTraceContextSupport.effectiveSubjectId(trace));
        event.put("subjectType", RelayTraceContextSupport.subjectType(trace));
        event.put("authMode", RelayTraceContextSupport.authMode(trace));
        event.put("callerType", RelayTraceContextSupport.callerType(trace));
        event.put("deploymentId", RelayTraceContextSupport.deploymentId(trace));
        event.put("customerId", RelayTraceContextSupport.customerId(trace));
        event.put("tenantId", RelayTraceContextSupport.tenantId(trace));
        event.put("issuer", RelayTraceContextSupport.authIssuer(trace));
        event.put("sessionId", RelayTraceContextSupport.effectiveSessionId(trace));
        if (!RelayTraceContextSupport.grantedScopes(trace).isEmpty()) {
            event.put("grantedScopes", RelayTraceContextSupport.grantedScopes(trace));
        }
        event.put("success", success);
        if (StringUtils.hasText(errorCode)) {
            event.put("errorCode", errorCode);
        }
        event.put("latencyMs", latencyMs);
        log.info("relay_audit {}", event);
    }
}
