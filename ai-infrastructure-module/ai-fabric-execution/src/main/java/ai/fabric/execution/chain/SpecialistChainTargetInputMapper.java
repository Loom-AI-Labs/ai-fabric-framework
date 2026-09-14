package ai.fabric.execution.chain;

/** Application-owned typed mapping into one approved worker. */
public interface SpecialistChainTargetInputMapper<P, I> {

    SpecialistChainComponentId id();

    Class<P> chainRequestType();

    Class<I> targetInputType();

    I map(P chainRequest, SpecialistChainTargetRequest targetRequest);
}
