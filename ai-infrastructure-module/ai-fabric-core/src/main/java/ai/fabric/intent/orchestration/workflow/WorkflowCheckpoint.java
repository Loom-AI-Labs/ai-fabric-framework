package ai.fabric.intent.orchestration.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Protected application state; never a public result or authorization grant. */
public record WorkflowCheckpoint(String workId, String requestFingerprint, String authorityFingerprint,
                                 String definitionFingerprint, Instant deadline, long version,
                                 WorkflowAllowance.Usage usage, List<Step> steps, State state,
                                 String message, List<String> questions, String stopCode) {
    public enum State { RUNNING, COMPLETE, NEEDS_INPUT, UNSUPPORTED, CONFIRMATION_REQUIRED, STOPPED }
    public enum Effect { DISPATCHING, EXECUTED, REJECTED, CONFIRMATION_REQUIRED, OUTCOME_UNKNOWN }
    public record Step(String operationId, String action, boolean write, Map<String, Object> parameters,
                       String parameterFingerprint, Effect effect, Map<String, Object> observation, String code) {
        public Step {
            Objects.requireNonNull(operationId); Objects.requireNonNull(action);
            parameters = WorkflowValues.snapshot(parameters); observation = WorkflowValues.snapshot(observation);
            Objects.requireNonNull(parameterFingerprint); Objects.requireNonNull(effect);
        }
        @Override public String toString() { return "WorkflowStep[operationId=" + operationId + ",effect=" + effect + "]"; }
        public Step observed(Effect result, Map<String, Object> data, String reason) {
            return new Step(operationId, action, write, parameters, parameterFingerprint, result, data, reason);
        }
    }
    public WorkflowCheckpoint {
        Objects.requireNonNull(workId); Objects.requireNonNull(requestFingerprint);
        Objects.requireNonNull(authorityFingerprint); Objects.requireNonNull(definitionFingerprint);
        Objects.requireNonNull(deadline); Objects.requireNonNull(usage); Objects.requireNonNull(state);
        steps = List.copyOf(steps); questions = List.copyOf(questions);
        if (version < 0) throw new IllegalArgumentException("Invalid checkpoint version");
    }
    @Override public String toString() { return "WorkflowCheckpoint[workId=" + workId + ",state=" + state + ",version=" + version + "]"; }
}
