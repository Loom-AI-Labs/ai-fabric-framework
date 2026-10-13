package ai.fabric.intent.action;

import java.util.Map;

/**
 * Optional invocation-level refinement for an action whose static contract is
 * confirmable.
 */
public interface InvocationConfirmationPolicy {

    boolean requiresConfirmation(Map<String, Object> params, ActionContext context);
}
