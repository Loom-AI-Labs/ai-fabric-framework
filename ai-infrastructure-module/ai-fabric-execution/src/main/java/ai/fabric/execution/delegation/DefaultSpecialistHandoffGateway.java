package ai.fabric.execution.delegation;

import ai.fabric.execution.handoff.SpecialistHandoffFailure;
import ai.fabric.execution.handoff.SpecialistHandoffGateway;
import ai.fabric.execution.handoff.SpecialistHandoffRequest;
import ai.fabric.execution.handoff.SpecialistHandoffResult;
import ai.fabric.execution.specialist.SpecialistRegistry;
import ai.fabric.execution.specialist.client.SpecialistClientFactory;
import ai.fabric.execution.specialist.manifest.CanonicalJsonSupport;
import java.time.Clock;
import java.time.Duration;

/**
 * Enforces exact-version, one-level, read-only specialist handoff.
 */
public final class DefaultSpecialistHandoffGateway
    implements SpecialistHandoffGateway {

    public static final int MAX_DEPTH =
        OneLevelSpecialistTransitionEngine.MAX_DEPTH;
    public static final String DIAGNOSTIC_DEPTH =
        OneLevelSpecialistTransitionEngine.HANDOFF_DEPTH;
    public static final String DIAGNOSTIC_DEADLINE =
        OneLevelSpecialistTransitionEngine.DIAGNOSTIC_DEADLINE;

    private final OneLevelSpecialistTransitionEngine transitionEngine;

    public DefaultSpecialistHandoffGateway(
        SpecialistRegistry specialistRegistry,
        SpecialistClientFactory clientFactory,
        CanonicalJsonSupport canonicalJson,
        Clock clock,
        Duration resultTtl
    ) {
        this.transitionEngine = new OneLevelSpecialistTransitionEngine(
            specialistRegistry,
            clientFactory,
            canonicalJson,
            clock,
            resultTtl,
            OneLevelSpecialistTransitionEngine.Relation.HANDOFF
        );
    }

    @Override
    public <P, I, O> SpecialistHandoffResult<P, O> handoff(
        SpecialistHandoffRequest<P, I> request,
        Class<I> successorInputType,
        Class<O> successorOutputType
    ) {
        OneLevelSpecialistTransitionEngine.TransitionResult<P, O> result =
            transitionEngine.transition(
                new OneLevelSpecialistTransitionEngine.TransitionRequest<>(
                    request.predecessorExecution(),
                    request.successorSpecialistId(),
                    request.successorInput(),
                    request.trustedExecutionContext(),
                    request.deadline(),
                    request.idempotencyKey()
                ),
                successorInputType,
                successorOutputType
            );
        OneLevelSpecialistTransitionEngine.TransitionFailure failure =
            result.failure();
        return new SpecialistHandoffResult<>(
            result.transitionId(),
            result.sourceInvocationId(),
            result.sourceSpecialistId(),
            result.targetSpecialistId(),
            MAX_DEPTH,
            result.status(),
            result.sourceOutput(),
            result.targetExecution(),
            failure == null
                ? null
                : new SpecialistHandoffFailure(
                    failure.reason(),
                    failure.publicMessage(),
                    failure.retryable()
                ),
            result.replayed(),
            result.startedAt(),
            result.completedAt()
        );
    }
}
