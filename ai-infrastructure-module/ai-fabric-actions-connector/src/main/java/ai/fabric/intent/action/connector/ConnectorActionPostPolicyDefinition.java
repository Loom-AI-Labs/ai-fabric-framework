package ai.fabric.intent.action.connector;

public record ConnectorActionPostPolicyDefinition(
    String type,
    String targetRef,
    String eventType
) {
}
