package com.ai.fabric.realapps.behavior.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class BehaviorAccessControlConfiguration {

    @Bean
    EntityAccessPolicy behaviorAnalysisAccessPolicy() {
        return (authContext, entity) -> hasTrustedSubject(authContext)
            && "behavior-demo".equals(authContext.getTenantId())
            && authContext.getGrantedScopes() != null
            && authContext.getGrantedScopes().contains("specialist:behavior-risk-analyst@1")
            && "rag:intent".equals(value(entity, "resourceId"))
            && "READ".equalsIgnoreCase(value(entity, "operationType"));
    }

    private static boolean hasTrustedSubject(AIAccessSubjectContext authContext) {
        return authContext != null
            && StringUtils.hasText(authContext.getSubjectId())
            && "TRUSTED_APPLICATION".equals(authContext.getAuthMode())
            && "SERVICE".equals(authContext.getCallerType());
    }

    private static String value(Map<String, Object> entity, String key) {
        Object value = entity == null ? null : entity.get(key);
        return value == null ? null : value.toString();
    }
}
