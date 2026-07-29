package ai.fabric.execution.specialist.client;

import ai.fabric.execution.specialist.SpecialistId;

public interface SpecialistClientFactory {

    <I, O> SpecialistClient<I, O> bind(
        SpecialistId specialistId,
        Class<I> inputType,
        Class<O> outputType
    );
}
