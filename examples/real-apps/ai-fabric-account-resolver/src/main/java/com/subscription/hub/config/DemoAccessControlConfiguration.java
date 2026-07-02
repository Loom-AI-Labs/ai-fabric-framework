package com.subscription.hub.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Demo access-control hook for the public Account Resolver app.
 *
 * <p>AI Fabric fails closed when an application does not provide an {@link EntityAccessPolicy}.
 * Production applications should replace this with subject, tenant, scope, and resource checks
 * backed by their own identity system.</p>
 */
@Configuration(proxyBeanMethods = false)
class DemoAccessControlConfiguration {

    private static final String RESOURCE_RAG_INTENT = "rag:intent";
    private static final String OPERATION_READ = "READ";

    @Bean
    EntityAccessPolicy accountResolverDemoEntityAccessPolicy() {
        return (authContext, entity) -> hasSubject(authContext)
            && RESOURCE_RAG_INTENT.equals(stringValue(entity, "resourceId"))
            && OPERATION_READ.equalsIgnoreCase(stringValue(entity, "operationType"));
    }

    private static boolean hasSubject(ai.fabric.dto.AIAccessSubjectContext authContext) {
        if (authContext == null) {
            return false;
        }
        return hasText(authContext.getSubjectId()) || hasText(authContext.getSessionId());
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String stringValue(Map<String, Object> entity, String key) {
        if (entity == null || key == null) {
            return null;
        }
        Object value = entity.get(key);
        return value == null ? null : value.toString();
    }
}
