package ai.fabric.execution.specialist;

import java.util.List;
import java.util.Optional;

public interface SpecialistRegistry {

    Optional<SpecialistDefinition<?, ?>> find(SpecialistId id);

    List<SpecialistDefinition<?, ?>> list();

    default SpecialistDefinition<?, ?> require(SpecialistId id) {
        return find(id).orElseThrow(() ->
            new SpecialistNotFoundException("No specialist is registered for " + id)
        );
    }

    final class SpecialistNotFoundException extends RuntimeException {
        public SpecialistNotFoundException(String message) {
            super(message);
        }
    }
}
