package ai.fabric.intent.action.invocation;

/**
 * Final non-bypassable boundary for AI Fabric action execution.
 */
public interface GovernedActionInvocationService {

    GovernedActionInvocationOutcome invoke(GovernedActionInvocation invocation);
}
