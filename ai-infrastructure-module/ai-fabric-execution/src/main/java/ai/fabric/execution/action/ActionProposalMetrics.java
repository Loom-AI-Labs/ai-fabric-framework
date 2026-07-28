package ai.fabric.execution.action;

public interface ActionProposalMetrics {

    void record(
        String event,
        String actionName,
        ActionProposalReceiptStatus status
    );

    static ActionProposalMetrics noop() {
        return (event, actionName, status) -> {};
    }
}
