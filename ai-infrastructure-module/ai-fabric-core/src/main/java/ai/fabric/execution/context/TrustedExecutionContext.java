package ai.fabric.execution.context;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Server-owned identity and authority attached to an orchestration request.
 *
 * <p>Applications must construct this value from authenticated runtime state. It must never be
 * populated directly from an untrusted request body.</p>
 */
public record TrustedExecutionContext(
    ExecutionPrincipal initiator,
    ExecutionSubjectRef subject,
    ExecutionSource source,
    String tenantId,
    String deploymentId,
    Set<String> grantedScopes,
    String correlationId,
    Instant authenticatedAt
) {
    public TrustedExecutionContext {
        Objects.requireNonNull(initiator, "initiator is required");
        Objects.requireNonNull(source, "source is required");
        tenantId = normalizeOptional(tenantId);
        deploymentId = normalizeOptional(deploymentId);
        grantedScopes = immutableScopes(grantedScopes);
        correlationId = normalizeOptional(correlationId);
        if (correlationId == null) {
            correlationId = "exec-" + UUID.randomUUID();
        }
        validatePrincipalForSource(source, initiator.principalType());
    }

    public static TrustedExecutionContext application(
        String serviceId,
        ExecutionSubjectRef subject,
        String tenantId,
        Set<String> grantedScopes
    ) {
        return new TrustedExecutionContext(
            new ExecutionPrincipal(serviceId, ExecutionPrincipalType.SERVICE),
            subject,
            ExecutionSource.APPLICATION,
            tenantId,
            null,
            grantedScopes,
            null,
            Instant.now()
        );
    }

    private static void validatePrincipalForSource(
        ExecutionSource source,
        ExecutionPrincipalType principalType
    ) {
        if (source == ExecutionSource.INTERACTIVE
            && principalType != ExecutionPrincipalType.END_USER) {
            throw new IllegalArgumentException(
                "INTERACTIVE execution requires an END_USER initiator"
            );
        }
        if (source != ExecutionSource.INTERACTIVE
            && principalType == ExecutionPrincipalType.END_USER) {
            throw new IllegalArgumentException(
                source + " execution requires a SERVICE or SYSTEM initiator"
            );
        }
    }

    private static Set<String> immutableScopes(Set<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            String candidate = normalizeOptional(scope);
            if (candidate != null) {
                normalized.add(candidate);
            }
        }
        return Collections.unmodifiableSet(normalized);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }
}
