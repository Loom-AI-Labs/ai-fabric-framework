package ai.fabric.execution.state;

import ai.fabric.execution.context.ExecutionPrincipalType;
import ai.fabric.execution.context.ExecutionSource;
import ai.fabric.execution.gateway.AIExecutionRequest;
import ai.fabric.execution.specialist.RegisteredSpecialist;
import java.util.Objects;

/**
 * V1 boundary for restart-safe, machine-triggered, read-only specialist jobs.
 */
public final class DurableExecutionSubmissionPolicy {

    public void validate(
        AIExecutionRequest<?> request,
        RegisteredSpecialist specialist
    ) {
        Objects.requireNonNull(request, "request is required");
        Objects.requireNonNull(specialist, "specialist is required");
        var context = request.trustedExecutionContext();
        if (context.source() == ExecutionSource.INTERACTIVE) {
            throw unsupported(
                "DURABLE_INTERACTIVE_UNSUPPORTED",
                "Durable V1 jobs require a machine-triggered execution source."
            );
        }
        if (context.initiator().principalType()
            == ExecutionPrincipalType.END_USER) {
            throw unsupported(
                "DURABLE_PRINCIPAL_UNSUPPORTED",
                "Durable V1 jobs require a service or system principal."
            );
        }
        if (context.subject() == null) {
            throw unsupported(
                "DURABLE_SUBJECT_REQUIRED",
                "Durable V1 jobs require an application-owned subject."
            );
        }
        if (request.conversationBinding() != null) {
            throw unsupported(
                "DURABLE_CONVERSATION_UNSUPPORTED",
                "Durable V1 jobs cannot bind a chat conversation."
            );
        }
        if (specialist.definition().executionProfile().writeEnabled()) {
            throw unsupported(
                "DURABLE_WRITE_UNSUPPORTED",
                "Durable V1 jobs require a read-only specialist."
            );
        }
    }

    private UnsupportedDurableExecutionException unsupported(
        String reason,
        String message
    ) {
        return new UnsupportedDurableExecutionException(reason, message);
    }

    public static final class UnsupportedDurableExecutionException
        extends RuntimeException {

        private final String reason;

        public UnsupportedDurableExecutionException(
            String reason,
            String message
        ) {
            super(message);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
