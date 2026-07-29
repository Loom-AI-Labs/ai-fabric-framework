package ai.fabric.execution.gateway;

import ai.fabric.execution.context.TrustedExecutionContext;
import ai.fabric.execution.specialist.SpecialistId;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Objects;

/**
 * Typed, untrusted response plus server-owned current context for one waiting invocation.
 */
public record AIExecutionResumeRequest(
    SpecialistId specialistId,
    String invocationId,
    String requestId,
    JsonNode response,
    TrustedExecutionContext trustedExecutionContext,
    String idempotencyKey
) {
    private static final int MAX_IDEMPOTENCY_KEY_CHARACTERS = 200;

    public AIExecutionResumeRequest {
        Objects.requireNonNull(specialistId, "specialistId is required");
        invocationId = requireText(invocationId, "invocationId");
        requestId = requireText(requestId, "requestId");
        Objects.requireNonNull(response, "response is required");
        response = response.deepCopy();
        Objects.requireNonNull(
            trustedExecutionContext,
            "trustedExecutionContext is required"
        );
        idempotencyKey = requireText(idempotencyKey, "idempotencyKey");
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_CHARACTERS) {
            throw new IllegalArgumentException(
                "idempotencyKey must not exceed "
                    + MAX_IDEMPOTENCY_KEY_CHARACTERS + " characters"
            );
        }
    }

    @Override
    public JsonNode response() {
        return response.deepCopy();
    }

    private static String requireText(String value, String field) {
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
