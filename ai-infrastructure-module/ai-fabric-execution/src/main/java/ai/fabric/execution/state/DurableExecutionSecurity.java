package ai.fabric.execution.state;

import ai.fabric.execution.action.ActionProposalSecurity;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;

/**
 * Protected-payload and keyed-fingerprint support for durable execution state.
 */
public final class DurableExecutionSecurity {

    private static final SpecialistId SCOPE_ID =
        SpecialistId.of("durable-execution-scope", "1");

    private final ActionProposalSecurity delegate;

    public DurableExecutionSecurity(
        ObjectMapper objectMapper,
        String encryptionSecret,
        String fingerprintSecret
    ) {
        this.delegate = new ActionProposalSecurity(
            objectMapper,
            encryptionSecret,
            fingerprintSecret
        );
    }

    public String protect(Map<String, Object> payload, String binding) {
        return delegate.protect(payload, binding);
    }

    public Map<String, Object> unprotect(
        String payload,
        String binding
    ) {
        return delegate.unprotect(payload, binding);
    }

    public String accessFingerprint(TrustedExecutionContext context) {
        Objects.requireNonNull(context, "trusted context is required");
        return delegate.idempotencyFingerprint(
            context,
            SCOPE_ID,
            "access:" + context.source().name()
        );
    }

    public String idempotencyFingerprint(
        TrustedExecutionContext context,
        String idempotencyKey
    ) {
        Objects.requireNonNull(context, "trusted context is required");
        return delegate.idempotencyFingerprint(
            context,
            SCOPE_ID,
            "idempotency:" + requireText(
                idempotencyKey,
                "idempotencyKey"
            )
        );
    }

    public String canonicalHash(Object value) {
        return delegate.canonicalHash(value);
    }

    public boolean sameFingerprint(String left, String right) {
        return delegate.sameFingerprint(left, right);
    }

    private String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(
            value,
            field + " is required"
        ).trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
