package ai.fabric.execution.specialist.client;

import java.util.Objects;

/** A completed schema-bound invocation could not satisfy its typed contract. */
public final class SpecialistOutputBindingException extends RuntimeException {

    private final String invocationId;

    public SpecialistOutputBindingException(
        String invocationId,
        Class<?> outputType
    ) {
        super("Specialist output does not satisfy the typed contract "
            + Objects.requireNonNull(outputType, "outputType is required")
                .getName());
        this.invocationId = Objects.requireNonNull(
            invocationId,
            "invocationId is required"
        );
    }

    public String invocationId() {
        return invocationId;
    }
}
