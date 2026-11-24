package ai.fabric.llm.structured;

import ai.fabric.dto.ProviderOutcome;
import java.util.Objects;

/** An explicitly safe transport observation; never copy a provider exception body into the message. */
public final class ProviderOutcomeException extends RuntimeException {
    private final ProviderOutcome outcome;
    public ProviderOutcomeException(ProviderOutcome outcome) {
        super("Provider call did not complete"); this.outcome = Objects.requireNonNull(outcome);
    }
    public ProviderOutcome outcome() { return outcome; }
}
