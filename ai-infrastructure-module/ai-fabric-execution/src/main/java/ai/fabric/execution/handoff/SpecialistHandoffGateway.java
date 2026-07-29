package ai.fabric.execution.handoff;

public interface SpecialistHandoffGateway {

    <P, I, O> SpecialistHandoffResult<P, O> handoff(
        SpecialistHandoffRequest<P, I> request,
        Class<I> successorInputType,
        Class<O> successorOutputType
    );
}
