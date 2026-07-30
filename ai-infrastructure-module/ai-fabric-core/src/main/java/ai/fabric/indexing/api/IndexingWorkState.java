package ai.fabric.indexing.api;

/**
 * Public lifecycle state for a durable indexing work item.
 */
public enum IndexingWorkState {
    COMMIT_PENDING(false, false, false),
    PENDING(false, false, false),
    PROCESSING(false, false, false),
    COMPLETED(true, true, false),
    SUPERSEDED(true, true, false),
    DEAD_LETTER(true, false, true);

    private final boolean terminal;
    private final boolean successfulTerminal;
    private final boolean requiresOperatorReview;

    IndexingWorkState(
        boolean terminal,
        boolean successfulTerminal,
        boolean requiresOperatorReview
    ) {
        this.terminal = terminal;
        this.successfulTerminal = successfulTerminal;
        this.requiresOperatorReview = requiresOperatorReview;
    }

    public boolean isTerminal() {
        return terminal;
    }

    public boolean isSuccessfulTerminal() {
        return successfulTerminal;
    }

    public boolean requiresOperatorReview() {
        return requiresOperatorReview;
    }

    public boolean isInProgress() {
        return !terminal;
    }
}
