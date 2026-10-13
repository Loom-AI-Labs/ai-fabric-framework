package ai.fabric.intent.orchestration.control;

import org.springframework.util.StringUtils;

/**
 * Typed fail-closed rejection raised by a host-owned orchestration turn guard.
 */
public final class OrchestrationControlException extends RuntimeException {

    private final String code;

    public OrchestrationControlException(String code, String message) {
        super(StringUtils.hasText(message) ? message : "Orchestration is not allowed for the current control state.");
        this.code = StringUtils.hasText(code) ? code.trim() : "ORCHESTRATION_CONTROL_DENIED";
    }

    public String code() {
        return code;
    }
}
