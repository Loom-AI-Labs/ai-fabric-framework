package ai.fabric.execution.specialist;

import java.util.List;
import java.util.Optional;

public interface SpecialistRegistry {

    Optional<SpecialistDefinition<?, ?>> find(SpecialistId id);

    List<SpecialistDefinition<?, ?>> list();

    default Optional<RegisteredSpecialist> findRegistered(SpecialistId id) {
        return find(id).map(definition -> new RegisteredSpecialist(
            definition,
            SpecialistDefinitionSource.JAVA,
            SpecialistDefinitionFingerprinter.fingerprint(definition),
            "java:" + definition.id(),
            java.util.Map.of()
        ));
    }

    default RegisteredSpecialist requireRegistered(SpecialistId id) {
        return findRegistered(id).orElseThrow(() ->
            new SpecialistNotFoundException("No specialist is registered for " + id)
        );
    }

    default List<RegisteredSpecialist> listRegistered() {
        return list().stream()
            .map(definition -> requireRegistered(definition.id()))
            .toList();
    }

    default String registryContentHash() {
        String declaration = listRegistered().stream()
            .sorted(java.util.Comparator.comparing(value ->
                value.id().toString()
            ))
            .map(value -> value.id() + ":" + value.contentHash())
            .collect(java.util.stream.Collectors.joining("\n"));
        return ai.fabric.execution.specialist.manifest.CanonicalJsonSupport
            .sha256(declaration);
    }

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
