package ai.fabric.intent.action;

/**
 * Shared grounding rules for successful action results.
 */
public final class ActionGroundingSupport {

    private ActionGroundingSupport() {
    }

    /**
     * Whether the action result cannot, by itself, ground a user-facing answer.
     *
     * <p>An explicit connector decision takes precedence over payload inference. Without an
     * explicit decision, an empty canonical list requires additional grounding.</p>
     */
    public static boolean requiresAdditionalGrounding(ActionResult result) {
        if (result == null || !result.isSuccess()) {
            return true;
        }
        if (result.getGroundingSufficiency() == ActionGroundingSufficiency.SUFFICIENT) {
            return false;
        }
        if (result.getGroundingSufficiency() == ActionGroundingSufficiency.INSUFFICIENT) {
            return true;
        }
        return result.getData() instanceof ActionListPayload listPayload && listPayload.isEmpty();
    }
}
