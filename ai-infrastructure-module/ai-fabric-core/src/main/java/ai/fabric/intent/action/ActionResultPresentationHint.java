package ai.fabric.intent.action;

/**
 * Presentation hint for how shells should render an action result.
 *
 * <p>This is advisory metadata only. Runtime execution and governance remain
 * driven by action policy, not UI hints.</p>
 */
public enum ActionResultPresentationHint {
    DEFAULT,
    LIST,
    DETAIL,
    STATUS
}
