package com.ai.fabric.realapps.incident.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import com.ai.fabric.realapps.incident.service.IncidentCallerScopeResolver;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class IncidentAccessControlConfiguration {

    private static final Set<String> APPROVED_SCOPES = Set.of(
        "specialist:service-health-reader@1",
        "specialist:change-risk-reader@1",
        "specialist:incident-intake@1",
        "specialist:incident-conversation-manager@1",
        "specialist:service-health-reader@2",
        "specialist:change-risk-reader@2",
        "specialist:incident-intake@2",
        "specialist:incident-conversation-manager@2",
        "specialist:incident-chain-manager@3"
    );

    @Bean
    EntityAccessPolicy incidentEntityAccessPolicy(
        IncidentCallerScopeResolver scopes
    ) {
        return (authContext, entity) -> trustedIncidentContext(
            authContext,
            scopes
        )
            && "rag:intent".equals(value(entity, "resourceId"))
            && "READ".equalsIgnoreCase(value(entity, "operationType"));
    }

    private boolean trustedIncidentContext(
        AIAccessSubjectContext context,
        IncidentCallerScopeResolver scopes
    ) {
        if (context == null || context.getGrantedScopes() == null
            || context.getGrantedScopes().stream().noneMatch(APPROVED_SCOPES::contains)) return false;
        try {
            scopes.resolve(context);
            return true;
        } catch (IllegalArgumentException denied) {
            return false;
        }
    }

    private String value(Map<String, Object> entity, String key) {
        Object selected = entity == null ? null : entity.get(key);
        return selected == null ? null : selected.toString();
    }
}
