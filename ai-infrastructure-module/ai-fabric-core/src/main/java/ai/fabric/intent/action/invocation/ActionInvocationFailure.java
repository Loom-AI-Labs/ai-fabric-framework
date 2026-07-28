package ai.fabric.intent.action.invocation;

/**
 * Sanitized failure returned by the governed action boundary.
 */
public record ActionInvocationFailure(
    String reason,
    String publicMessage,
    boolean retryable
) {
}
