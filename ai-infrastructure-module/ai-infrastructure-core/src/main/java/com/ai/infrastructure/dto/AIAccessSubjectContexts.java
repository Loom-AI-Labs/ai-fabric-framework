package com.ai.infrastructure.dto;

import org.springframework.util.StringUtils;

/**
 * Common canonical auth-context builders.
 */
public final class AIAccessSubjectContexts {

    public static final String SUBJECT_TYPE_SYSTEM = "SYSTEM_SUBJECT";
    public static final String AUTH_MODE_INTERNAL_SYSTEM = "INTERNAL_SYSTEM";
    public static final String CALLER_TYPE_INTERNAL_SYSTEM = "INTERNAL_SYSTEM";

    private AIAccessSubjectContexts() {
    }

    public static AIAccessSubjectContext system(String subjectId) {
        if (!StringUtils.hasText(subjectId)) {
            throw new IllegalArgumentException("System subjectId is required");
        }
        return AIAccessSubjectContext.builder()
            .subjectId(subjectId.trim())
            .subjectType(SUBJECT_TYPE_SYSTEM)
            .authMode(AUTH_MODE_INTERNAL_SYSTEM)
            .callerType(CALLER_TYPE_INTERNAL_SYSTEM)
            .build();
    }
}
