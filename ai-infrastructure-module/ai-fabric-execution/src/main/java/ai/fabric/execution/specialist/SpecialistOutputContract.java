package ai.fabric.execution.specialist;

/**
 * Explicit contract used by the structured specialist output finalizer.
 */
public sealed interface SpecialistOutputContract
    permits JavaTypeOutputContract, JsonSchemaOutputContract {

    String promptInstructions();
}
