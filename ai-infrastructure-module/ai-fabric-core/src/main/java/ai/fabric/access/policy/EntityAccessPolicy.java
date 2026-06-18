package ai.fabric.access.policy;

import ai.fabric.dto.AIAccessSubjectContext;

import java.util.Map;

/**
 * Infrastructure hook for entity-level access control decisions.
 * Implementations live in customer applications and are injected optionally.
 */
@FunctionalInterface
public interface EntityAccessPolicy {

    /**
     * Determine whether the verified subject can access the supplied entity.
     *
     * @param authContext canonical verified auth context for the request subject
     * @param entity immutable view of the entity metadata for the access decision
     * @return {@code true} when access should be granted, {@code false} otherwise
     */
    boolean canAccess(AIAccessSubjectContext authContext, Map<String, Object> entity);

    /**
     * Optional callback invoked when access is denied. Implementations can override to emit
     * custom audit records or telemetry. The default callback has no side effects.
     *
     * @param authContext canonical verified auth context for the denied subject
     * @param entity entity metadata that triggered the denial
     * @param reason machine readable reason (infrastructure supplied)
     */
    default void logAccessDenied(AIAccessSubjectContext authContext, Map<String, Object> entity, String reason) {
        // intentionally left blank - customers can override
    }
}
