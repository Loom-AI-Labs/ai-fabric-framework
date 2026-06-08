package com.ai.fabric.runtime.authz;

import com.ai.infrastructure.access.policy.EntityAccessPolicy;
import com.ai.infrastructure.dto.AIAccessSubjectContext;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * Simple runtime authz policy for platform-managed deployments that only need verified caller identity.
 *
 * <p>This policy stays fail-closed for anonymous or malformed subjects. It is intentionally explicit and must be
 * selected by configuration; {@code REMOTE_HTTP} remains the default runtime authz mode.</p>
 */
public class AllowVerifiedEntityAccessPolicy implements EntityAccessPolicy {

    private static final String ANONYMOUS_SUBJECT_TYPE = "ANONYMOUS_SESSION";

    @Override
    public boolean canAccess(AIAccessSubjectContext authContext, Map<String, Object> entity) {
        if (authContext == null || entity == null) {
            return false;
        }
        if (!StringUtils.hasText(authContext.getSubjectId())) {
            return false;
        }
        if (!StringUtils.hasText(authContext.getAuthMode())) {
            return false;
        }
        String subjectType = authContext.getSubjectType();
        return StringUtils.hasText(subjectType)
            && !ANONYMOUS_SUBJECT_TYPE.equalsIgnoreCase(subjectType.trim());
    }
}
