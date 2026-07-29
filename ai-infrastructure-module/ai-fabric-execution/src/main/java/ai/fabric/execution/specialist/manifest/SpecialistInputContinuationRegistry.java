package ai.fabric.execution.specialist.manifest;

import ai.fabric.execution.input.SpecialistInputContinuation;
import java.util.List;
import java.util.Objects;

public final class SpecialistInputContinuationRegistry {

    private final SpecialistExtensionRegistrySupport<
        SpecialistInputContinuation<?>
    > registry;

    public SpecialistInputContinuationRegistry(
        List<SpecialistInputContinuation<?>> continuations
    ) {
        this.registry = new SpecialistExtensionRegistrySupport<>(
            continuations,
            SpecialistInputContinuation::id,
            "input continuation"
        );
        this.registry.list().forEach(this::validate);
    }

    public SpecialistInputContinuation<?> require(String id) {
        return registry.require(id);
    }

    public List<SpecialistInputContinuation<?>> list() {
        return registry.list();
    }

    private void validate(SpecialistInputContinuation<?> continuation) {
        Objects.requireNonNull(
            continuation.inputType(),
            "input continuation inputType is required"
        );
        if (continuation.responseSchemas() == null
            || continuation.responseSchemas().isEmpty()) {
            throw new SpecialistManifestException(
                "INPUT_CONTINUATION_SCHEMA_REQUIRED",
                "Input continuation " + continuation.id()
                    + " must advertise at least one response schema.",
                "extension:" + continuation.id()
            );
        }
        if (continuation.responseSchemas().stream()
            .anyMatch(Objects::isNull)) {
            throw new SpecialistManifestException(
                "INPUT_CONTINUATION_SCHEMA_INVALID",
                "Input continuation " + continuation.id()
                    + " contains a null response schema.",
                "extension:" + continuation.id()
            );
        }
    }
}
