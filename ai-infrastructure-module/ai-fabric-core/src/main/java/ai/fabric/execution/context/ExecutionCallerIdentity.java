package ai.fabric.execution.context;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;

/** Verified caller facts supplied by the application, never model or request-body claims. */
public record ExecutionCallerIdentity(
    String subjectType,
    String authMode,
    String callerType,
    String sessionId,
    String customerId,
    String issuer,
    List<String> audiences,
    Instant expiresAt
) {
    public ExecutionCallerIdentity {
        subjectType = normalized(subjectType);
        authMode = normalized(authMode);
        callerType = normalized(callerType);
        sessionId = normalized(sessionId);
        customerId = normalized(customerId);
        issuer = normalized(issuer);
        audiences = audiences == null ? List.of() : audiences.stream()
            .map(ExecutionCallerIdentity::normalized).filter(java.util.Objects::nonNull).distinct().toList();
    }

    private static String normalized(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Stable, token-free representation inside an already protected execution envelope. */
    public Map<String, Object> snapshot() {
        var values = new LinkedHashMap<String, Object>();
        put(values, "subjectType", subjectType); put(values, "authMode", authMode);
        put(values, "callerType", callerType); put(values, "sessionId", sessionId);
        put(values, "customerId", customerId); put(values, "issuer", issuer);
        values.put("audiences", audiences);
        put(values, "expiresAt", expiresAt == null ? null : expiresAt.toString());
        return Map.copyOf(values);
    }

    public static ExecutionCallerIdentity restore(Object payload) {
        if (payload == null) return null;
        if (!(payload instanceof Map<?, ?> values)
            || !Set.of("subjectType", "authMode", "callerType", "sessionId", "customerId", "issuer", "audiences", "expiresAt")
                .containsAll(values.keySet())) throw new IllegalArgumentException("Invalid protected caller identity");
        Object rawAudiences = values.get("audiences");
        if (rawAudiences != null && (!(rawAudiences instanceof List<?> list)
            || list.stream().anyMatch(value -> !(value instanceof String))))
            throw new IllegalArgumentException("Invalid protected caller audiences");
        List<String> audiences = rawAudiences == null ? List.of() : ((List<?>) rawAudiences).stream().map(String.class::cast).toList();
        String expiry = text(values, "expiresAt");
        return new ExecutionCallerIdentity(text(values, "subjectType"), text(values, "authMode"), text(values, "callerType"),
            text(values, "sessionId"), text(values, "customerId"), text(values, "issuer"), audiences,
            expiry == null ? null : Instant.parse(expiry));
    }

    private static void put(Map<String, Object> values, String key, String value) {
        if (value != null) values.put(key, value);
    }

    private static String text(Map<?, ?> values, String key) {
        Object value = values.get(key);
        if (value == null) return null;
        if (!(value instanceof String text)) throw new IllegalArgumentException("Invalid protected caller field: " + key);
        return normalized(text);
    }
}
