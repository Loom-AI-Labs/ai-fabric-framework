package com.ai.fabric.realapps.incident.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class IncidentAccessControlConfiguration {

    private static final Set<String> APPROVED_SCOPES = Set.of(
        "specialist:service-health-reader@1",
        "specialist:change-risk-reader@1",
        "specialist:incident-intake@1",
        "specialist:incident-conversation-manager@1"
    );

    @Bean
    EntityAccessPolicy incidentEntityAccessPolicy() {
        return (authContext, entity) -> trustedIncidentContext(authContext)
            && "rag:intent".equals(value(entity, "resourceId"))
            && "READ".equalsIgnoreCase(value(entity, "operationType"));
    }

    private boolean trustedIncidentContext(AIAccessSubjectContext context) {
        return context != null
            && StringUtils.hasText(context.getSubjectId())
            && Set.of("TRUSTED_APPLICATION", "TRUSTED_INTERACTIVE")
                .contains(context.getAuthMode())
            && Set.of("SERVICE", "END_USER").contains(context.getCallerType())
            && "public-demo".equals(context.getTenantId())
            && "incident-investigation-room".equals(context.getDeploymentId())
            && context.getGrantedScopes() != null
            && context.getGrantedScopes().stream().anyMatch(APPROVED_SCOPES::contains);
    }

    private String value(Map<String, Object> entity, String key) {
        Object selected = entity == null ? null : entity.get(key);
        return selected == null ? null : selected.toString();
    }
}
