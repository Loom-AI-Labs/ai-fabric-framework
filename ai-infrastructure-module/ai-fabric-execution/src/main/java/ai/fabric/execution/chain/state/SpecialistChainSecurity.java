package ai.fabric.execution.chain.state;

import ai.fabric.execution.action.ActionProposalSecurity;
import ai.fabric.execution.chain.SpecialistChainId;
import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Objects;

/** Encryption and scoped fingerprints for chain state and replay. */
public final class SpecialistChainSecurity {

    private static final SpecialistId SECURITY_SCOPE =
        SpecialistId.of("specialist-chain-state", "1");

    private final ActionProposalSecurity delegate;

    public SpecialistChainSecurity(
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

    public String protect(Map<String, Object> value, String binding) {
        return delegate.protect(value, requireText(binding, "binding"));
    }

    public Map<String, Object> unprotect(String value, String binding) {
        return delegate.unprotect(value, requireText(binding, "binding"));
    }

    public String accessFingerprint(TrustedExecutionContext context) {
        Objects.requireNonNull(context, "trusted context is required");
        return delegate.idempotencyFingerprint(
            context,
            SECURITY_SCOPE,
            "access:" + context.source().name()
        );
    }

    public String idempotencyFingerprint(
        TrustedExecutionContext context,
        SpecialistChainId chainId,
        String idempotencyKey
    ) {
        Objects.requireNonNull(context, "trusted context is required");
        Objects.requireNonNull(chainId, "chainId is required");
        return delegate.idempotencyFingerprint(
            context,
            SECURITY_SCOPE,
            "chain:" + chainId + ":" + requireText(
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
