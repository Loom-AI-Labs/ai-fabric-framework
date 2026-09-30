package ai.fabric.intent.action;

/**
 * Trusted action-owned override for whether a successful result can ground an answer.
 *
 * <p>When absent, orchestration derives sufficiency from the typed action payload. Use
 * {@link #SUFFICIENT} only when the action result, including an empty list, fully answers the
 * requested fact. Use {@link #INSUFFICIENT} when successful execution still requires another
 * grounding source.</p>
 */
public enum ActionGroundingSufficiency {
    SUFFICIENT,
    INSUFFICIENT
}
