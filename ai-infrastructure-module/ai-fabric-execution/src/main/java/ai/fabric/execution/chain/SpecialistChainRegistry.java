package ai.fabric.execution.chain;

import java.util.List;
import java.util.Optional;

/** Lookup boundary for startup-validated specialist chains. */
public interface SpecialistChainRegistry {

    Optional<RegisteredSpecialistChain> find(SpecialistChainId id);

    List<RegisteredSpecialistChain> list();

    default String registryContentHash() {
        String declaration = list().stream()
            .sorted(java.util.Comparator.comparing(value ->
                value.id().toString()
            ))
            .map(value -> value.id() + ":" + value.contentHash())
            .collect(java.util.stream.Collectors.joining("\n"));
        return ai.fabric.execution.specialist.manifest.CanonicalJsonSupport
            .sha256(declaration);
    }

    default RegisteredSpecialistChain require(SpecialistChainId id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException(
            "Specialist chain is not registered: " + id
        ));
    }
}
