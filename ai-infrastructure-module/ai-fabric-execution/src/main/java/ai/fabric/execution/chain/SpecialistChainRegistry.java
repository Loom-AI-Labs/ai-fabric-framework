package ai.fabric.execution.chain;

import java.util.List;
import java.util.Optional;

/** Lookup boundary for startup-validated specialist chains. */
public interface SpecialistChainRegistry {

    Optional<RegisteredSpecialistChain> find(SpecialistChainId id);

    List<RegisteredSpecialistChain> list();

    default RegisteredSpecialistChain require(SpecialistChainId id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(
            "Specialist chain is not registered: " + id
        ));
    }
}
