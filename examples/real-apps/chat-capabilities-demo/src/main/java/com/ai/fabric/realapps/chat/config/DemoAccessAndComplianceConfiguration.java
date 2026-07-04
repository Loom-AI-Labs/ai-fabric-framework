package com.ai.fabric.realapps.chat.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.compliance.policy.ComplianceCheckProvider;
import ai.fabric.compliance.policy.ComplianceCheckResult;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Demo-only access + compliance configuration.
 *
 * <p>Production deployments should implement strict access control and compliance checks.</p>
 */
@Configuration(proxyBeanMethods = false)
public class DemoAccessAndComplianceConfiguration {

    @Bean
    EntityAccessPolicy demoEntityAccessPolicy() {
        return (authContext, entity) -> {
            if (authContext == null
                || (!StringUtils.hasText(authContext.getSubjectId()) && !StringUtils.hasText(authContext.getSessionId()))) {
                return false;
            }
            String resourceId = lower(value(entity, "resourceId"));
            String operationType = lower(value(entity, "operationType"));
            if (!ALLOWED_OPERATIONS.contains(operationType)) {
                return false;
            }
            if ("rag:intent".equals(resourceId)) {
                return true;
            }
            if (resourceId != null && resourceId.startsWith("vectorspace:")) {
                String vectorSpace = resourceId.substring("vectorspace:".length());
                return ALLOWED_VECTOR_SPACES.contains(vectorSpace);
            }
            return ALLOWED_RESOURCE_IDS.contains(resourceId);
        };
    }

    @Bean
    ComplianceCheckProvider demoComplianceCheckProvider() {
        return request -> ComplianceCheckResult.builder()
            .compliant(true)
            .details("Demo compliance provider approval")
            .build();
    }

    private static final Set<String> ALLOWED_OPERATIONS = Set.of(
        "read",
        "search",
        "create",
        "update",
        "delete",
        "write",
        "execute",
        "sync"
    );

    private static final Set<String> ALLOWED_RESOURCE_IDS = Set.of(
        "product",
        "review",
        "policy",
        "coupon",
        "cart",
        "order",
        "purchase_order",
        "support_ticket",
        "shipment",
        "payment",
        "account",
        "address"
    );

    private static final Set<String> ALLOWED_VECTOR_SPACES = Set.of(
        "product",
        "review",
        "policy",
        "coupon"
    );

    private static String value(Map<String, Object> entity, String key) {
        if (entity == null || key == null) {
            return null;
        }
        Object value = entity.get(key);
        return value != null ? value.toString() : null;
    }

    private static String lower(String value) {
        return StringUtils.hasText(value) ? value.trim().toLowerCase(Locale.ROOT) : null;
    }
}
