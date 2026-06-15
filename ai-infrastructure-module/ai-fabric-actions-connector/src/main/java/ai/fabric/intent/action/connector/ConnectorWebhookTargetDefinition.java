package ai.fabric.intent.action.connector;

public record ConnectorWebhookTargetDefinition(
    String id,
    String urlSecretRef,
    String signingSecretRef,
    Integer timeoutMs,
    Integer maxAttempts
) {
}
