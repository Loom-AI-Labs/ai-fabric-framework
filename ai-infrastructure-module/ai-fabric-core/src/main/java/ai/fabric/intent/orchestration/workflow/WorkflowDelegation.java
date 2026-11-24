package ai.fabric.intent.orchestration.workflow;

import java.util.List;

/** Model-selected subtask references; the host must resolve and authorize every reference. */
public record WorkflowDelegation(String profileId, String task, List<String> workReferences,
                                 List<String> sourceReferences) {
    public WorkflowDelegation {
        workReferences = workReferences == null ? List.of() : List.copyOf(workReferences);
        sourceReferences = sourceReferences == null ? List.of() : List.copyOf(sourceReferences);
        if (profileId == null || profileId.isBlank() || task == null || task.isBlank() || task.length() > 4000
            || workReferences.size() > 128 || sourceReferences.size() > 128
            || workReferences.stream().anyMatch(s -> s == null || s.isBlank())
            || sourceReferences.stream().anyMatch(s -> s == null || s.isBlank()))
            throw new IllegalArgumentException("Invalid delegated task execution references");
    }
}
