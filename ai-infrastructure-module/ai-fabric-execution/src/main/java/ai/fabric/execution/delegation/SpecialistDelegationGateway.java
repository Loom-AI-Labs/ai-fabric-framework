package ai.fabric.execution.delegation;

public interface SpecialistDelegationGateway {

    <P, I, O> SpecialistDelegationResult<P, O> delegate(
        SpecialistDelegationRequest<P, I> request,
        Class<I> targetInputType,
        Class<O> targetOutputType
    );
}
