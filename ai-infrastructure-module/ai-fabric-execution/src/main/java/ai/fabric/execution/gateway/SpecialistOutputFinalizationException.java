package ai.fabric.execution.gateway;

import java.util.Map;

/**
 * Visible failure from the explicit structured specialist output stage.
 */
public final class SpecialistOutputFinalizationException
    extends RuntimeException {

    private final String reason;
    private final boolean retryable;
    private final Map<String, Object> diagnostics;

    public SpecialistOutputFinalizationException(
        String reason,
        String publicMessage,
        boolean retryable,
        Map<String, Object> diagnostics
    ) {
        super(publicMessage);
        this.reason = reason;
        this.retryable = retryable;
        this.diagnostics = diagnostics == null
            ? Map.of()
            : Map.copyOf(diagnostics);
    }

    public String reason() {
        return reason;
    }

    public boolean retryable() {
        return retryable;
    }

    public Map<String, Object> diagnostics() {
        return diagnostics;
    }
}
