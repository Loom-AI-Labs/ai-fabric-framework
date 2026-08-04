package com.ai.fabric.realapps.deploymentguard.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import com.ai.fabric.realapps.deploymentguard.domain.DeploymentKnowledgeCatalog;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class DeploymentGuardAccessControlConfiguration {

    private static final String SPECIALIST_SCOPE =
        "specialist:deployment-knowledge-reader@1";

    @Bean
    EntityAccessPolicy deploymentGuardEntityAccessPolicy(
        DeploymentKnowledgeCatalog catalog
    ) {
        return (authContext, entity) -> trustedContext(authContext, catalog)
            && "rag:intent".equals(value(entity, "resourceId"))
            && "READ".equalsIgnoreCase(value(entity, "operationType"));
    }

    private boolean trustedContext(
        AIAccessSubjectContext context,
        DeploymentKnowledgeCatalog catalog
    ) {
        return context != null
            && StringUtils.hasText(context.getSubjectId())
            && "TRUSTED_APPLICATION".equals(context.getAuthMode())
            && "SERVICE".equals(context.getCallerType())
            && catalog.contexts().stream().anyMatch(candidate ->
                candidate.tenantId().equals(context.getTenantId())
                    && candidate.deploymentId().equals(context.getDeploymentId())
            )
            && context.getGrantedScopes() != null
            && context.getGrantedScopes().contains(SPECIALIST_SCOPE);
    }

    private String value(Map<String, Object> entity, String key) {
        Object selected = entity == null ? null : entity.get(key);
        return selected == null ? null : selected.toString();
    }
}
