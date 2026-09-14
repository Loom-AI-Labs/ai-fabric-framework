package ai.fabric.execution.chain;

/** Whether a chain may or must own a backend conversation turn. */
public enum SpecialistChainConversationPolicy {
    DISABLED,
    OPTIONAL,
    REQUIRED
}
