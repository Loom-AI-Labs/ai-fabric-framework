package com.ai.fabric.examples.governedactions.config;

import ai.fabric.access.policy.EntityAccessPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration(proxyBeanMethods = false)
public class QuickstartAccessControlConfiguration {

    @Bean
    public EntityAccessPolicy quickstartAccessPolicy() {

        return (authContext, entity) ->
                authContext != null
                        && authContext.getSubjectId() != null
                        && !authContext.getSubjectId().isBlank()
                        && "rag:intent".equals(entity.get("resourceId"))
                        && "READ".equalsIgnoreCase(
                        String.valueOf(entity.get("operationType"))
                );
    }
}