package ai.fabric.chat.it.actions;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class ConfirmableActionExecutionLog {

    private static final CopyOnWriteArrayList<String> EXECUTIONS = new CopyOnWriteArrayList<>();

    private ConfirmableActionExecutionLog() {
    }

    static void record(String actionName) {
        EXECUTIONS.add(actionName);
    }

    static void reset() {
        EXECUTIONS.clear();
    }

    static List<String> getExecutions() {
        return List.copyOf(EXECUTIONS);
    }
}
