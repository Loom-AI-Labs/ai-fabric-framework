package com.ai.fabric.realapps.mcpops.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
public class McpAccessControlConfiguration {

    private static final String SPECIALIST_SCOPE =
        "specialist:mcp-operations-specialist@1";

    @Bean
    EntityAccessPolicy mcpOperationsEntityAccessPolicy() {
        return (authContext, entity) -> trusted(authContext)
            && "rag:intent".equals(value(entity, "resourceId"))
            && "READ".equalsIgnoreCase(value(entity, "operationType"));
    }

    private boolean trusted(AIAccessSubjectContext context) {
        return context != null
            && StringUtils.hasText(context.getSubjectId())
            && "TRUSTED_INTERACTIVE".equals(context.getAuthMode())
            && "END_USER".equals(context.getCallerType())
            && "public-demo".equals(context.getTenantId())
            && "mcp-operations-assistant".equals(context.getDeploymentId())
            && context.getGrantedScopes() != null
            && context.getGrantedScopes().contains(SPECIALIST_SCOPE);
    }

    private String value(Map<String, Object> entity, String key) {
        Object selected = entity != null ? entity.get(key) : null;
        return selected != null ? selected.toString() : null;
    }
}
