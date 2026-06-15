package ai.fabric.intent.action.confirmation;

public record ConfirmationInterceptorRule(
    String name,
    ConfirmationInterceptorTrigger trigger,
    ConfirmationInterceptorDecision decision,
    ConfirmationInterceptorStackPolicy stackPolicy
) {
    public ConfirmationInterceptorRule {
        stackPolicy = stackPolicy != null ? stackPolicy : ConfirmationInterceptorStackPolicy.NONE;
    }
}
