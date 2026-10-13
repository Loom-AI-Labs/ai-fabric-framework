package com.ai.fabric.realapps.handover;

import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessSubjectContext;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
class ConversationHandoverAccessControlConfiguration {

    private static final String RAG_INTENT = "rag:intent";
    private static final String READ = "READ";

    @Bean
    EntityAccessPolicy conversationHandoverEntityAccessPolicy() {
        return (subject, entity) -> hasDemoSession(subject)
            && RAG_INTENT.equals(value(entity, "resourceId"))
            && READ.equalsIgnoreCase(value(entity, "operationType"));
    }

    private static boolean hasDemoSession(AIAccessSubjectContext subject) {
        return subject != null
            && StringUtils.hasText(subject.getSubjectId())
            && StringUtils.hasText(subject.getSessionId())
            && subject.getSubjectId().equals(subject.getSessionId());
    }

    private static String value(Map<String, Object> entity, String key) {
        Object value = entity == null ? null : entity.get(key);
        return value == null ? null : value.toString();
    }
}
