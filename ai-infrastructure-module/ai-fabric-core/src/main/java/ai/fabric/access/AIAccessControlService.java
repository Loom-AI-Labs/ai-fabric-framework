package ai.fabric.access;

import ai.fabric.cache.AICacheNames;
import ai.fabric.access.policy.EntityAccessPolicy;
import ai.fabric.dto.AIAccessControlRequest;
import ai.fabric.dto.AIAccessControlResponse;
import ai.fabric.dto.AIAccessSubjectContext;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

/**
 * Minimal infrastructure access control service: validate request, delegate to customer hook,
 * and fail closed when hooks are unavailable.
 */
@Slf4j
@RequiredArgsConstructor
public class AIAccessControlService {

    private static final String ACCESS_DECISIONS_CACHE = AICacheNames.ACCESS_DECISIONS;

    private final Clock clock;
    private final EntityAccessPolicy entityAccessPolicy;
    private final Cache accessDecisionCache;

    public AIAccessControlService(Clock clock, EntityAccessPolicy entityAccessPolicy) {
        this(clock, entityAccessPolicy, (Cache) null);
    }

    public AIAccessControlService(Clock clock,
                                  EntityAccessPolicy entityAccessPolicy,
                                  CacheManager cacheManager) {
        this(clock,
            entityAccessPolicy,
            cacheManager == null ? null : cacheManager.getCache(ACCESS_DECISIONS_CACHE));
    }

    public AIAccessControlResponse checkAccess(AIAccessControlRequest request) {
        long started = System.nanoTime();
        Objects.requireNonNull(request, "access request must not be null");

        EntityAccessPolicy policy = requirePolicy();
        AIAccessSubjectContext authContext = requireAuthContext(request);
        String subjectId = resolveSubjectId(authContext);

        LocalDateTime evaluationTimestamp = Optional.ofNullable(request.getTimestamp())
            .orElseGet(() -> LocalDateTime.now(clock));
        Map<String, Object> entityContext = buildEntityContext(request, evaluationTimestamp);
        AccessDecisionCacheKey cacheKey = buildCacheKey(request, authContext);
        Decision decision = lookupCachedDecision(cacheKey);
        boolean fromCache = decision != null;
        if (decision == null) {
            decision = evaluateAccess(policy, authContext, entityContext);
            cacheDecision(cacheKey, decision);
        }
        if (!decision.granted()) {
            logDenied(policy, authContext, entityContext);
        }

        long durationMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        return AIAccessControlResponse.builder()
            .requestId(request.getRequestId())
            .subjectId(subjectId)
            .resourceId(Objects.toString(entityContext.get("resourceId"), null))
            .operationType(Objects.toString(entityContext.get("operationType"), null))
            .accessGranted(decision.granted())
            .fromCache(fromCache)
            .accessDecision(decision.granted() ? "GRANT" : "DENY")
            .processingTimeMs(durationMs)
            .timestamp(evaluationTimestamp)
            .success(!decision.hookFailed())
            .errorMessage(decision.hookFailed() ? decision.errorMessage() : null)
            .build();
    }

    private EntityAccessPolicy requirePolicy() {
        if (entityAccessPolicy == null) {
            throw new IllegalStateException("""
                No EntityAccessPolicy bean available. Register a bean implementing \
                ai.fabric.access.policy.EntityAccessPolicy to evaluate access decisions.""");
        }
        return entityAccessPolicy;
    }

    private AIAccessSubjectContext requireAuthContext(AIAccessControlRequest request) {
        AIAccessSubjectContext authContext = request.getAuthContext();
        if (authContext != null && (hasText(authContext.getSubjectId()) || hasText(authContext.getSessionId()))) {
            return authContext;
        }
        throw new IllegalArgumentException("authContext.subjectId or authContext.sessionId must be provided");
    }

    private String resolveSubjectId(AIAccessSubjectContext authContext) {
        if (hasText(authContext.getSubjectId())) {
            return authContext.getSubjectId().trim();
        }
        return authContext.getSessionId().trim();
    }

    private Map<String, Object> buildEntityContext(AIAccessControlRequest request, LocalDateTime timestamp) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("resourceId", Optional.ofNullable(request.getResourceId()).orElse("UNKNOWN"));
        context.put("operationType", Optional.ofNullable(request.getOperationType()).orElse("READ"));
        context.put("timestamp", timestamp);
        if (request.getContext() != null) {
            context.put("context", request.getContext());
        }
        if (request.getPurpose() != null) {
            context.put("purpose", request.getPurpose());
        }
        if (request.getMetadata() != null && !request.getMetadata().isEmpty()) {
            Map<String, Object> normalizedMetadata = normalizeMap(request.getMetadata());
            if (!normalizedMetadata.isEmpty()) {
                context.put("metadata", normalizedMetadata);
            }
        }
        if (request.getUserAttributes() != null && !request.getUserAttributes().isEmpty()) {
            Map<String, Object> normalizedAttributes = normalizeMap(request.getUserAttributes());
            if (!normalizedAttributes.isEmpty()) {
                context.put("userAttributes", normalizedAttributes);
            }
        }
        if (request.getAuthContext() != null) {
            context.put("authContext", request.getAuthContext());
        }
        return context;
    }

    private Decision evaluateAccess(EntityAccessPolicy policy, AIAccessSubjectContext authContext, Map<String, Object> entityContext) {
        try {
            boolean granted = policy.canAccess(authContext, Collections.unmodifiableMap(entityContext));
            return new Decision(granted, false, null);
        } catch (Exception ex) {
            log.warn("EntityAccessPolicy threw an exception for subject {}: {}", resolveSubjectId(authContext), ex.getMessage());
            return new Decision(false, true, ex.getMessage());
        }
    }

    private void logDenied(EntityAccessPolicy policy, AIAccessSubjectContext authContext, Map<String, Object> entityContext) {
        try {
            policy.logAccessDenied(authContext, Collections.unmodifiableMap(entityContext), "POLICY_DENIED");
        } catch (Exception ex) {
            log.debug("EntityAccessPolicy.logAccessDenied failed: {}", ex.getMessage());
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private Decision lookupCachedDecision(AccessDecisionCacheKey cacheKey) {
        if (cacheKey == null || accessDecisionCache == null) {
            return null;
        }
        return accessDecisionCache.get(cacheKey, Decision.class);
    }

    private void cacheDecision(AccessDecisionCacheKey cacheKey, Decision decision) {
        if (cacheKey == null || accessDecisionCache == null || decision == null || decision.hookFailed()) {
            return;
        }
        accessDecisionCache.put(cacheKey, decision);
    }

    private AccessDecisionCacheKey buildCacheKey(AIAccessControlRequest request, AIAccessSubjectContext authContext) {
        if (request == null || authContext == null || accessDecisionCache == null) {
            return null;
        }
        return new AccessDecisionCacheKey(
            normalizeText(authContext.getSubjectId()),
            normalizeText(authContext.getSessionId()),
            normalizeText(authContext.getSubjectType()),
            normalizeText(authContext.getAuthMode()),
            normalizeText(authContext.getCallerType()),
            normalizeText(authContext.getDeploymentId()),
            normalizeText(authContext.getCustomerId()),
            normalizeText(authContext.getTenantId()),
            normalizeText(authContext.getIssuer()),
            immutableList(authContext.getGrantedScopes()),
            immutableList(authContext.getAudiences()),
            normalizeText(authContext.getExpiresAt()),
            normalizeText(request.getResourceId()),
            normalizeText(request.getOperationType()),
            normalizeText(request.getContext()),
            normalizeText(request.getPurpose()),
            normalizeMap(request.getMetadata()),
            normalizeMap(request.getUserAttributes()),
            request.getTimestamp()
        );
    }

    private String normalizeText(String value) {
        return hasText(value) ? value.trim() : null;
    }

    private List<String> immutableList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(text -> !text.isEmpty())
            .toList();
    }

    private Map<String, Object> normalizeMap(Map<String, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            Object normalizedValue = normalizeValue(value);
            if (normalizedValue != null) {
                normalized.put(key, normalizedValue);
            }
        });
        return normalized.isEmpty() ? Map.of() : Map.copyOf(normalized);
    }

    private Object normalizeValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> nested = new LinkedHashMap<>();
            rawMap.forEach((key, nestedValue) -> {
                if (key == null || nestedValue == null) {
                    return;
                }
                Object normalizedNestedValue = normalizeValue(nestedValue);
                if (normalizedNestedValue != null) {
                    nested.put(String.valueOf(key), normalizedNestedValue);
                }
            });
            return nested.isEmpty() ? Map.of() : Map.copyOf(nested);
        }
        if (value instanceof List<?> rawList) {
            return rawList.stream()
                .map(this::normalizeValue)
                .filter(Objects::nonNull)
                .toList();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> normalized = new ArrayList<>();
            iterable.forEach(item -> {
                Object normalizedItem = normalizeValue(item);
                if (normalizedItem != null) {
                    normalized.add(normalizedItem);
                }
            });
            return List.copyOf(normalized);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<Object> normalized = new ArrayList<>(length);
            for (int index = 0; index < length; index++) {
                Object normalizedItem = normalizeValue(java.lang.reflect.Array.get(value, index));
                if (normalizedItem != null) {
                    normalized.add(normalizedItem);
                }
            }
            return List.copyOf(normalized);
        }
        if (value instanceof CharSequence text) {
            String normalized = text.toString().trim();
            return normalized.isEmpty() ? null : normalized;
        }
        if (value instanceof Number
            || value instanceof Boolean
            || value instanceof Enum<?>
            || value instanceof LocalDateTime) {
            return value;
        }
        return String.valueOf(value);
    }

    private record AccessDecisionCacheKey(
        String subjectId,
        String sessionId,
        String subjectType,
        String authMode,
        String callerType,
        String deploymentId,
        String customerId,
        String tenantId,
        String issuer,
        List<String> grantedScopes,
        List<String> audiences,
        String expiresAt,
        String resourceId,
        String operationType,
        String context,
        String purpose,
        Map<String, Object> metadata,
        Map<String, Object> userAttributes,
        LocalDateTime explicitTimestamp
    ) { }

    private record Decision(boolean granted, boolean hookFailed, String errorMessage) { }
}
