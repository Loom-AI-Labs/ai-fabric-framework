package ai.fabric.intent.action;

import java.util.Map;

/**
 * Resolves the effective confirmation requirement without weakening the
 * static action contract when a dynamic policy is absent or fails.
 */
public final class ActionConfirmationPolicySupport {

    private ActionConfirmationPolicySupport() {
    }

    public static boolean requiresConfirmation(
        AIActionHandler handler,
        AIActionMetaData metadata,
        Map<String, Object> params,
        ActionContext context
    ) {
        boolean staticallyRequired = metadata != null && metadata.isConfirmationRequired();
        try {
            staticallyRequired = staticallyRequired
                || (handler != null && handler.requiresConfirmation());
        } catch (RuntimeException ignored) {
            return true;
        }
        if (!staticallyRequired) {
            return false;
        }
        if (!(handler instanceof InvocationConfirmationPolicy policy)) {
            return true;
        }
        try {
            return policy.requiresConfirmation(params == null ? Map.of() : params, context);
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}
